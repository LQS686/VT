#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdlib>

#include <cstdint>
#include <cstring>
#include <cstdio>
#include <map>
#include <unordered_map>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "dobby.h"
#include "lsplant.hpp"
#include "xz.h"

#define LOG_TAG "VT-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
bool g_initialized = false;

void ExemptHiddenApis(JNIEnv *env) {
    jclass vm_runtime = env->FindClass("dalvik/system/VMRuntime");
    if (vm_runtime == nullptr) {
        env->ExceptionClear();
        LOGE("VMRuntime not found");
        return;
    }
    jmethodID get_runtime = env->GetStaticMethodID(
            vm_runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jmethodID set_exemptions = env->GetMethodID(
            vm_runtime, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (get_runtime == nullptr || set_exemptions == nullptr) {
        env->ExceptionClear();
        LOGE("setHiddenApiExemptions not found");
        return;
    }
    jobject runtime = env->CallStaticObjectMethod(vm_runtime, get_runtime);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray exemptions = env->NewObjectArray(1, string_class, env->NewStringUTF("L"));
    env->CallVoidMethod(runtime, set_exemptions, exemptions);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("setHiddenApiExemptions failed");
    }
    env->DeleteLocalRef(exemptions);
    env->DeleteLocalRef(runtime);
    env->DeleteLocalRef(vm_runtime);
}

void *InlineHook(void *target, void *hooker) {
    void *backup = nullptr;
    int result = DobbyHook(target, hooker, &backup);
    return result == 0 ? backup : nullptr;
}

bool InlineUnhook(void *func) {
    bool result = DobbyDestroy(func) == 0;
    return result;
}

// ---------------------------------------------------------------------------
// libart symbol resolution
//
// Release libart.so has no .symtab. It carries a compressed MiniDebugInfo
// (.gnu_debugdata) instead. LSPlant needs internal symbols such as
// GetMethodShorty, so resolve exact and prefixed names from that section.
// ---------------------------------------------------------------------------

struct LibArtInfo {
    uintptr_t bias = 0;
    std::string path;
};

LibArtInfo g_libart;
std::once_flag g_symbols_once;
std::map<std::string, uintptr_t> g_mini_symbols;
std::vector<uint8_t> g_mini_storage;

int FindLibArt(struct dl_phdr_info *info, size_t, void *) {
    if (info->dlpi_name == nullptr) return 0;
    std::string_view path(info->dlpi_name);
    if (path.find("libart.so") == std::string_view::npos) return 0;
    g_libart.bias = info->dlpi_addr;
    g_libart.path = std::string(path);
    return 1;
}

std::vector<uint8_t> ReadFile(const std::string &path) {
    std::vector<uint8_t> data;
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return data;
    struct stat st {};
    if (fstat(fd, &st) == 0 && st.st_size > 0) {
        data.resize(static_cast<size_t>(st.st_size));
        size_t offset = 0;
        while (offset < data.size()) {
            ssize_t read_size = read(fd, data.data() + offset, data.size() - offset);
            if (read_size <= 0) break;
            offset += static_cast<size_t>(read_size);
        }
        if (offset != data.size()) data.clear();
    }
    close(fd);
    return data;
}

bool DecompressXz(const uint8_t *input, size_t input_size, std::vector<uint8_t> &output) {
    static std::once_flag crc_once;
    std::call_once(crc_once, []() { xz_crc32_init(); });

    struct xz_dec *decoder = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (decoder == nullptr) {
        LOGE("xz_dec_init failed");
        return false;
    }
    output.assign(16 * 1024 * 1024, 0);
    struct xz_buf buffer {};
    buffer.in = input;
    buffer.in_size = input_size;
    buffer.in_pos = 0;
    buffer.out = output.data();
    buffer.out_size = output.size();
    buffer.out_pos = 0;
    enum xz_ret result = xz_dec_run(decoder, &buffer);
    bool ok = result == XZ_STREAM_END || result == XZ_UNSUPPORTED_CHECK ||
              (result == XZ_OK && buffer.in_pos == buffer.in_size && buffer.out_pos > 0);
    if (ok) {
        output.resize(buffer.out_pos);
    } else {
        LOGE("xz decode failed: %d (in=%zu/%zu out=%zu/%zu)", result,
             buffer.in_pos, buffer.in_size, buffer.out_pos, buffer.out_size);
        output.clear();
    }
    xz_dec_end(decoder);
    return ok;
}

void ParseMiniDebugInfo(const std::vector<uint8_t> &file) {
    if (file.size() < sizeof(ElfW(Ehdr))) return;
    auto *header = reinterpret_cast<const ElfW(Ehdr) *>(file.data());
    if (header->e_shoff == 0 || header->e_shnum == 0 ||
        header->e_shoff + header->e_shnum * sizeof(ElfW(Shdr)) > file.size()) {
        return;
    }
    auto *sections = reinterpret_cast<const ElfW(Shdr) *>(file.data() + header->e_shoff);
    if (header->e_shstrndx >= header->e_shnum) return;
    const char *shstr = reinterpret_cast<const char *>(
            file.data() + sections[header->e_shstrndx].sh_offset);

    // Exported symbols live in .dynsym; internal ones only in MiniDebugInfo.
    for (int i = 0; i < header->e_shnum; i++) {
        const ElfW(Shdr) &symtab = sections[i];
        if (symtab.sh_type != SHT_DYNSYM || symtab.sh_link >= header->e_shnum) continue;
        const ElfW(Shdr) &strtab = sections[symtab.sh_link];
        if (symtab.sh_offset + symtab.sh_size > file.size() ||
            strtab.sh_offset + strtab.sh_size > file.size()) {
            continue;
        }
        auto *symbols = reinterpret_cast<const ElfW(Sym) *>(file.data() + symtab.sh_offset);
        const char *strings = reinterpret_cast<const char *>(file.data() + strtab.sh_offset);
        size_t count = symtab.sh_size / sizeof(ElfW(Sym));
        for (size_t index = 0; index < count; index++) {
            const ElfW(Sym) &symbol = symbols[index];
            if (symbol.st_name == 0 || symbol.st_value == 0) continue;
            const char *name = strings + symbol.st_name;
            if (name[0] == 0) continue;
            g_mini_symbols.emplace(name, symbol.st_value);
        }
    }

    const uint8_t *debugdata = nullptr;
    size_t debugsize = 0;
    for (int i = 0; i < header->e_shnum; i++) {
        const char *name = shstr + sections[i].sh_name;
        if (std::strcmp(name, ".gnu_debugdata") == 0) {
            debugdata = file.data() + sections[i].sh_offset;
            debugsize = sections[i].sh_size;
            break;
        }
    }
    if (debugdata == nullptr || !DecompressXz(debugdata, debugsize, g_mini_storage)) {
        LOGE("Cannot decompress .gnu_debugdata");
        return;
    }

    auto *mini = reinterpret_cast<const ElfW(Ehdr) *>(g_mini_storage.data());
    if (mini->e_shoff == 0 ||
        mini->e_shoff + mini->e_shnum * sizeof(ElfW(Shdr)) > g_mini_storage.size()) {
        LOGE("Invalid MiniDebugInfo ELF");
        return;
    }
    auto *mini_sections = reinterpret_cast<const ElfW(Shdr) *>(
            g_mini_storage.data() + mini->e_shoff);
    for (int i = 0; i < mini->e_shnum; i++) {
        const ElfW(Shdr) &symtab = mini_sections[i];
        if (symtab.sh_type != SHT_SYMTAB || symtab.sh_link >= mini->e_shnum) continue;
        const ElfW(Shdr) &strtab = mini_sections[symtab.sh_link];
        if (symtab.sh_offset + symtab.sh_size > g_mini_storage.size() ||
            strtab.sh_offset + strtab.sh_size > g_mini_storage.size()) {
            continue;
        }
        auto *symbols = reinterpret_cast<const ElfW(Sym) *>(
                g_mini_storage.data() + symtab.sh_offset);
        const char *strings = reinterpret_cast<const char *>(
                g_mini_storage.data() + strtab.sh_offset);
        size_t count = symtab.sh_size / sizeof(ElfW(Sym));
        for (size_t index = 0; index < count; index++) {
            const ElfW(Sym) &symbol = symbols[index];
            if (symbol.st_name == 0 || symbol.st_value == 0) continue;
            const char *name = strings + symbol.st_name;
            if (name[0] == 0) continue;
            g_mini_symbols.emplace(name, symbol.st_value);
        }
    }
    LOGI("MiniDebugInfo symbols: %zu", g_mini_symbols.size());
}

void LoadArtSymbols() {
    dl_iterate_phdr(FindLibArt, nullptr);
    std::vector<uint8_t> data;
    if (!g_libart.path.empty()) {
        data = ReadFile(g_libart.path);
    }
    if (data.empty()) {
        const char *fallbacks[] = {
                "/apex/com.android.art/lib64/libart.so",
                "/system/lib64/libart.so",
                "/apex/com.android.art/lib/libart.so",
                "/system/lib/libart.so",
        };
        for (const char *path : fallbacks) {
            data = ReadFile(path);
            if (!data.empty()) break;
        }
    }
    if (data.empty()) {
        LOGE("Cannot read libart.so (%s)", g_libart.path.c_str());
        return;
    }
    ParseMiniDebugInfo(data);
}

void *ResolveArtSymbol(std::string_view name) {
    std::string symbol(name);
    std::call_once(g_symbols_once, LoadArtSymbols);
    // Android 15 removed JitCodeCache::GarbageCollectCache; DoCollection has the
    // same contract and is the fallback LSPlant itself uses on newer ART.
    if (symbol == "_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE") {
        auto fallback = g_mini_symbols.find(
                "_ZN3art3jit12JitCodeCache12DoCollectionEPNS_6ThreadE");
        if (fallback != g_mini_symbols.end()) {
            return reinterpret_cast<void *>(g_libart.bias + fallback->second);
        }
    }
    auto found = g_mini_symbols.find(symbol);
    void *result = nullptr;
    if (found != g_mini_symbols.end()) {
        result = reinterpret_cast<void *>(g_libart.bias + found->second);
    }
    if (result == nullptr) {
        LOGI("unresolved symbol: %s", symbol.c_str());
    }
    return result;
}

void *ResolveArtPrefix(std::string_view prefix) {
    std::call_once(g_symbols_once, LoadArtSymbols);
    std::string key(prefix);
    auto found = g_mini_symbols.lower_bound(key);
    if (found == g_mini_symbols.end() || found->first.compare(0, key.size(), key) != 0) {
        return nullptr;
    }
    return reinterpret_cast<void *>(g_libart.bias + found->second);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ExemptHiddenApis(env);
    // Near-branch trampolines can be invalid under Android 16 BTI/PAC; use far trampolines.

    lsplant::InitInfo info{};
    info.inline_hooker = InlineHook;
    info.inline_unhooker = InlineUnhook;
    info.art_symbol_resolver = ResolveArtSymbol;
    info.art_symbol_prefix_resolver = ResolveArtPrefix;
    info.generated_class_name = "VTHooker_";
    info.generated_source_name = "VT";
    info.generated_field_name = "hooker";
    info.generated_method_name = "{target}";

    g_initialized = lsplant::Init(env, info);
    LOGI("LSPlant init: %s", g_initialized ? "ok" : "failed");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_nativeIsInitialized(JNIEnv *, jclass) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_vt_xposed_VtNative_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("LSPlant 6.4 + Dobby");
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_vt_xposed_VtNative_hook(JNIEnv *env, jclass, jobject target, jobject hooker) {
    if (!g_initialized || target == nullptr || hooker == nullptr) {
        return nullptr;
    }
    jclass hooker_class = env->GetObjectClass(hooker);
    jmethodID callback = env->GetMethodID(
            hooker_class, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (callback == nullptr) {
        env->ExceptionClear();
        LOGE("Hooker.callback not found");
        return nullptr;
    }
    jobject reflected_callback = env->ToReflectedMethod(hooker_class, callback, JNI_FALSE);
    jobject backup = lsplant::Hook(env, target, hooker, reflected_callback);
    env->DeleteLocalRef(reflected_callback);
    return backup;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_unhook(JNIEnv *env, jclass, jobject target) {
    if (!g_initialized || target == nullptr) {
        return JNI_FALSE;
    }
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_isHooked(JNIEnv *env, jclass, jobject target) {
    if (!g_initialized || target == nullptr) {
        return JNI_FALSE;
    }
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_makeDexTrusted(JNIEnv *, jclass, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_deoptimize(JNIEnv *env, jclass, jobject method) {
    if (!g_initialized || method == nullptr) return JNI_FALSE;
    return lsplant::Deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}


