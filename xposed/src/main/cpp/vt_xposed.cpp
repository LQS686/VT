#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unwind.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
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

// --- 最小原生崩溃探针（记录信号、maps 与 PC 回栈；记录后退出） ---
int g_crash_fd = -1;
char g_crash_alt_stack[64 * 1024];

void CrashWrite(int fd, const char *data, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, data, len);
        if (n <= 0) break;
        data += n;
        len -= static_cast<size_t>(n);
    }
}

void CrashWriteStr(int fd, const char *s) {
    CrashWrite(fd, s, strlen(s));
}

void CrashWriteHex(int fd, uintptr_t value) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    buf[0] = '0';
    buf[1] = 'x';
    for (int i = 0; i < static_cast<int>(sizeof(uintptr_t) * 2); ++i) {
        int shift = (static_cast<int>(sizeof(uintptr_t)) - 1 - i / 2) * 8;
        int nibble = (value >> shift) & 0xF;
        buf[2 + i] = static_cast<char>(nibble < 10 ? '0' + nibble : 'a' + nibble - 10);
    }
    buf[2 + sizeof(uintptr_t) * 2] = '\0';
    CrashWriteStr(fd, buf);
}

_Unwind_Reason_Code CrashUnwind(struct _Unwind_Context *ctx, void *arg) {
    int fd = *static_cast<int *>(arg);
    uintptr_t pc = static_cast<uintptr_t>(_Unwind_GetIP(ctx));
    if (pc == 0) return _URC_NO_REASON;
    CrashWriteStr(fd, "  pc=");
    CrashWriteHex(fd, pc);
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(pc), &info) != 0 && info.dli_fname != nullptr) {
        CrashWriteStr(fd, " ");
        CrashWriteStr(fd, info.dli_fname);
        if (info.dli_sname != nullptr) {
            CrashWriteStr(fd, "!");
            CrashWriteStr(fd, info.dli_sname);
        }
    }
    CrashWriteStr(fd, "\n");
    return _URC_NO_REASON;
}

void CrashHandler(int sig, siginfo_t *info, void *) {
    int fd = g_crash_fd;
    if (fd < 0) _exit(128 + sig);
    char header[256];
    int n = snprintf(
            header, sizeof(header),
            "\n=== VT_NATIVE_CRASH pid=%d tid=%ld sig=%d code=%d addr=%p ===\n",
            getpid(), static_cast<long>(syscall(SYS_gettid)), sig,
            info != nullptr ? info->si_code : 0,
            info != nullptr ? info->si_addr : nullptr);
    if (n > 0) CrashWrite(fd, header, static_cast<size_t>(n));
    int maps = open("/proc/self/maps", O_RDONLY);
    if (maps >= 0) {
        char buf[4096];
        ssize_t r;
        while ((r = read(maps, buf, sizeof(buf))) > 0) {
            CrashWrite(fd, buf, static_cast<size_t>(r));
        }
        close(maps);
    }
    CrashWriteStr(fd, "--- backtrace ---\n");
    _Unwind_Backtrace(CrashUnwind, &fd);
    CrashWriteStr(fd, "=== END ===\n");
    _exit(128 + sig);
}

// --- 诊断用原生调用追踪：只记录 SecSDK 关注的环境检查原语 ---
int g_trace_fd = -1;
std::atomic<int> g_trace_getuid{0};
std::string g_env_host_pkg;
std::string g_env_guest_pkg;
std::string g_env_real_apk;
std::atomic<bool> g_env_shim{false};
bool g_env_apk_spoof = true;
bool g_readlink_hooked = false;
bool g_openat_hooked = false;
bool g_open_hooked = false;

bool TraceInteresting(const char *s) {
    if (s == nullptr || *s == '\0') return false;
    static const char *const keys[] = {
            "/proc", "/system", "/data/app", ".apk", "su", "magisk", "xposed",
            "frida", "virtual", "maps", "status", "cmdline", "getprop", "busybox",
            "mount", "ps", "which", "/data/user", "io.vt", "app_process"
    };
    for (const char *key : keys) {
        if (strstr(s, key) != nullptr) return true;
    }
    return false;
}

void TraceLine(const char *fmt, ...) {
    int fd = g_trace_fd;
    if (fd < 0) return;
    char buf[512];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (n > 0) CrashWrite(fd, buf, static_cast<size_t>(n < static_cast<int>(sizeof(buf)) ? n : sizeof(buf) - 1));
}

void TraceCaller(void *returnAddress, char *out, size_t size) {
    if (out == nullptr || size == 0) return;
    Dl_info info{};
    if (returnAddress != nullptr && dladdr(returnAddress, &info) != 0 &&
        info.dli_fname != nullptr) {
        snprintf(out, size, "%s", info.dli_fname);
    } else {
        snprintf(out, size, "?");
    }
}

bool IsGuestNativeCaller(void *returnAddress);

uid_t (*orig_getuid)() = nullptr;
uid_t trace_getuid() {
    uid_t value = orig_getuid != nullptr ? orig_getuid() : static_cast<uid_t>(-1);
    int count = g_trace_getuid.fetch_add(1);
    if (count < 500) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("getuid=%u caller=%s\n", static_cast<unsigned>(value), caller);
    }
    return value;
}

FILE *(*orig_fopen)(const char *, const char *) = nullptr;
FILE *trace_fopen(const char *path, const char *mode) {
    if (TraceInteresting(path)) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("fopen %s mode=%s caller=%s\n", path, mode != nullptr ? mode : "", caller);
    }
    return orig_fopen != nullptr ? orig_fopen(path, mode) : nullptr;
}

int (*orig_stat)(const char *, struct stat *) = nullptr;
int trace_stat(const char *path, struct stat *buf) {
    if (TraceInteresting(path)) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("stat %s caller=%s\n", path, caller);
    }
    return orig_stat != nullptr ? orig_stat(path, buf) : -1;
}

int (*orig_lstat)(const char *, struct stat *) = nullptr;
int trace_lstat(const char *path, struct stat *buf) {
    if (TraceInteresting(path)) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("lstat %s caller=%s\n", path, caller);
    }
    return orig_lstat != nullptr ? orig_lstat(path, buf) : -1;
}

int (*orig_access)(const char *, int) = nullptr;
int trace_access(const char *path, int mode) {
    if (TraceInteresting(path)) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("access %s mode=%d caller=%s\n", path, mode, caller);
    }
    return orig_access != nullptr ? orig_access(path, mode) : -1;
}

ssize_t (*orig_readlink)(const char *, char *, size_t) = nullptr;
ssize_t trace_readlink(const char *path, char *buf, size_t size) {
    ssize_t result = orig_readlink != nullptr ? orig_readlink(path, buf, size) : -1;
    if (result > 0 && buf != nullptr && size > 0) {
        size_t len = static_cast<size_t>(result) < size ? static_cast<size_t>(result) : size - 1;
        char target[256];
        size_t copy = len < sizeof(target) - 1 ? len : sizeof(target) - 1;
        memcpy(target, buf, copy);
        target[copy] = '\0';
        if (TraceInteresting(path) || TraceInteresting(target)) {
            char caller[256];
            TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
            TraceLine("readlink %s -> %s caller=%s\n", path, target, caller);
        }
        if (g_env_shim.load() && g_env_apk_spoof && strstr(path, "/fd/") != nullptr) {
            std::string real(target);
            if (real.find(g_env_host_pkg) != std::string::npos &&
                real.find(".apk") != std::string::npos && !g_env_real_apk.empty()) {
                if (g_env_real_apk.size() < size) {
                    memcpy(buf, g_env_real_apk.data(), g_env_real_apk.size());
                    TraceLine("readlink spoof apk %s -> %s\n", path, g_env_real_apk.c_str());
                    return static_cast<ssize_t>(g_env_real_apk.size());
                }
            }
        }
    }
    return result;
}

DIR *(*orig_opendir)(const char *) = nullptr;
DIR *trace_opendir(const char *path) {
    if (TraceInteresting(path)) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("opendir %s caller=%s\n", path, caller);
    }
    return orig_opendir != nullptr ? orig_opendir(path) : nullptr;
}

int (*orig_system)(const char *) = nullptr;
int trace_system(const char *command) {
    if (command != nullptr) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("system %s caller=%s\n", command, caller);
    }
    return orig_system != nullptr ? orig_system(command) : -1;
}

FILE *(*orig_popen)(const char *, const char *) = nullptr;
FILE *trace_popen(const char *command, const char *mode) {
    if (command != nullptr) {
        char caller[256];
        TraceCaller(__builtin_return_address(0), caller, sizeof(caller));
        TraceLine("popen %s mode=%s caller=%s\n", command, mode != nullptr ? mode : "", caller);
    }
    return orig_popen != nullptr ? orig_popen(command, mode) : nullptr;
}

bool IsMapsPath(const char *path) {
    if (path == nullptr) return false;
    size_t len = strlen(path);
    if (len < 6 || strncmp(path, "/proc/", 6) != 0) return false;
    return len >= 5 && strcmp(path + len - 5, "/maps") == 0;
}

bool IsGuestNativeCaller(void *returnAddress) {
    char caller[256];
    TraceCaller(returnAddress, caller, sizeof(caller));
    return strstr(caller, "libhoudini") != nullptr ||
           strstr(caller, "libmetasec") != nullptr ||
           strstr(caller, "liblynx") != nullptr ||
           strstr(caller, "libmsao") != nullptr ||
           strstr(caller, "metasec") != nullptr ||
           strstr(caller, "bytedance") != nullptr ||
           strstr(caller, "com.dragon") != nullptr ||
           strstr(caller, "com.phoenix") != nullptr;
}

void ReplaceAll(std::string &value, const std::string &from, const std::string &to) {
    if (from.empty()) return;
    size_t position = 0;
    while ((position = value.find(from, position)) != std::string::npos) {
        value.replace(position, from.size(), to);
        position += to.size();
    }
}

std::string FilterMaps(const std::string &data) {
    const std::string guestRoot = "/data/user/0/" + g_env_guest_pkg;
    const std::string hostData = "/data/data/" + g_env_host_pkg;
    const std::string hostUser = "/data/user/0/" + g_env_host_pkg;
    const std::string guestData = hostData + "/files/guests/" + g_env_guest_pkg;
    const std::string guestUser = hostUser + "/files/guests/" + g_env_guest_pkg;
    std::string result;
    size_t start = 0;
    while (start < data.size()) {
        size_t end = data.find('\n', start);
        if (end == std::string::npos) end = data.size(); else end++;
        std::string line = data.substr(start, end - start);
        if (line.find(g_env_host_pkg) != std::string::npos ||
            line.find("libappcore") != std::string::npos ||
            line.find("lsplant") != std::string::npos ||
            line.find("xposed") != std::string::npos) {
            start = end;
            continue;
        }
        ReplaceAll(line, guestData, guestRoot);
        ReplaceAll(line, guestUser, guestRoot);
        result += line;
        start = end;
    }
    return result;
}

int FilterMapsFd(int fd, const char *path) {
    std::string data;
    char buffer[8192];
    ssize_t count;
    while ((count = read(fd, buffer, sizeof(buffer))) > 0) {
        data.append(buffer, static_cast<size_t>(count));
    }
    close(fd);
    if (data.empty()) return -1;
    std::string filtered = FilterMaps(data);
    int memfd = static_cast<int>(syscall(SYS_memfd_create, "maps", 0));
    if (memfd < 0) return -1;
    size_t written = 0;
    while (written < filtered.size()) {
        ssize_t n = write(memfd, filtered.data() + written, filtered.size() - written);
        if (n <= 0) break;
        written += static_cast<size_t>(n);
    }
    lseek(memfd, 0, SEEK_SET);
    fcntl(memfd, F_SETFD, FD_CLOEXEC);
    TraceLine("maps filtered %s bytes=%zu->%zu\n", path, data.size(), filtered.size());
    return memfd;
}

int (*orig_openat)(int, const char *, int, mode_t) = nullptr;
int trace_openat(int dirfd, const char *path, int flags, mode_t mode) {
    int fd = orig_openat != nullptr ? orig_openat(dirfd, path, flags, mode) : -1;
    if (fd >= 0 && g_env_shim.load() && IsMapsPath(path)) {
        int filtered = FilterMapsFd(fd, path);
        if (filtered >= 0) return filtered;
        TraceLine("maps filter fallback %s\n", path);
        fd = static_cast<int>(orig_openat(dirfd, path, flags, mode));
    }
    return fd;
}

int (*orig_open)(const char *, int, mode_t) = nullptr;
int trace_open(const char *path, int flags, mode_t mode) {
    int fd = orig_open != nullptr ? orig_open(path, flags, mode) : -1;
    if (fd >= 0 && g_env_shim.load() && IsMapsPath(path)) {
        int filtered = FilterMapsFd(fd, path);
        if (filtered >= 0) return filtered;
        TraceLine("maps filter fallback %s\n", path);
        fd = static_cast<int>(orig_open != nullptr ? orig_open(path, flags, mode) : -1);
    }
    return fd;
}

void TraceHook(const char *name, void *replacement, void **original) {
    void *target = dlsym(RTLD_DEFAULT, name);
    if (target == nullptr) {
        TraceLine("trace hook missing %s\n", name);
        return;
    }
    int result = DobbyHook(target, replacement, original);
    TraceLine("trace hook %s=%d\n", name, result);
    if (result == 0) {
        if (strcmp(name, "readlink") == 0) g_readlink_hooked = true;
        if (strcmp(name, "openat") == 0) g_openat_hooked = true;
        if (strcmp(name, "open") == 0) g_open_hooked = true;
    }
}

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
Java_io_vt_xposed_VtNative_installCrashHandler(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return JNI_FALSE;
    const char *raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return JNI_FALSE;
    int fd = open(raw, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(path, raw);
    if (fd < 0) return JNI_FALSE;
    if (g_crash_fd >= 0) close(g_crash_fd);
    g_crash_fd = fd;

    stack_t ss{};
    ss.ss_sp = g_crash_alt_stack;
    ss.ss_size = sizeof(g_crash_alt_stack);
    sigaltstack(&ss, nullptr);

    struct sigaction action{};
    action.sa_sigaction = CrashHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, nullptr);
    sigaction(SIGABRT, &action, nullptr);
    sigaction(SIGBUS, &action, nullptr);
    sigaction(SIGILL, &action, nullptr);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_installNativeTrace(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return JNI_FALSE;
    const char *raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return JNI_FALSE;
    int fd = open(raw, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(path, raw);
    if (fd < 0) return JNI_FALSE;
    if (g_trace_fd >= 0) close(g_trace_fd);
    g_trace_fd = fd;
    TraceLine("\n=== VT_NATIVE_TRACE pid=%d ===\n", getpid());
    TraceHook("getuid", reinterpret_cast<void *>(trace_getuid),
              reinterpret_cast<void **>(&orig_getuid));
    TraceHook("fopen", reinterpret_cast<void *>(trace_fopen),
              reinterpret_cast<void **>(&orig_fopen));
    TraceHook("stat", reinterpret_cast<void *>(trace_stat),
              reinterpret_cast<void **>(&orig_stat));
    TraceHook("lstat", reinterpret_cast<void *>(trace_lstat),
              reinterpret_cast<void **>(&orig_lstat));
    TraceHook("access", reinterpret_cast<void *>(trace_access),
              reinterpret_cast<void **>(&orig_access));
    if (!g_readlink_hooked) {
        TraceHook("readlink", reinterpret_cast<void *>(trace_readlink),
                  reinterpret_cast<void **>(&orig_readlink));
    }
    TraceHook("opendir", reinterpret_cast<void *>(trace_opendir),
              reinterpret_cast<void **>(&orig_opendir));
    TraceHook("system", reinterpret_cast<void *>(trace_system),
              reinterpret_cast<void **>(&orig_system));
    TraceHook("popen", reinterpret_cast<void *>(trace_popen),
              reinterpret_cast<void **>(&orig_popen));
    if (!g_openat_hooked) {
        TraceHook("openat", reinterpret_cast<void *>(trace_openat),
                  reinterpret_cast<void **>(&orig_openat));
    }
    if (!g_open_hooked) {
        TraceHook("open", reinterpret_cast<void *>(trace_open),
                  reinterpret_cast<void **>(&orig_open));
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_vt_xposed_VtNative_installEnvShim(
        JNIEnv *env, jclass, jstring hostPackage, jstring guestPackage, jstring realApk) {
    auto toStd = [env](jstring value) -> std::string {
        if (value == nullptr) return {};
        const char *raw = env->GetStringUTFChars(value, nullptr);
        if (raw == nullptr) return {};
        std::string result(raw);
        env->ReleaseStringUTFChars(value, raw);
        return result;
    };
    g_env_host_pkg = toStd(hostPackage);
    g_env_guest_pkg = toStd(guestPackage);
    g_env_real_apk = toStd(realApk);
    g_env_shim.store(!g_env_host_pkg.empty() && !g_env_guest_pkg.empty());
    if (!g_readlink_hooked) {
        TraceHook("readlink", reinterpret_cast<void *>(trace_readlink),
                  reinterpret_cast<void **>(&orig_readlink));
    }
    if (!g_openat_hooked) {
        TraceHook("openat", reinterpret_cast<void *>(trace_openat),
                  reinterpret_cast<void **>(&orig_openat));
    }
    if (!g_open_hooked) {
        TraceHook("open", reinterpret_cast<void *>(trace_open),
                  reinterpret_cast<void **>(&orig_open));
    }
    TraceLine("env shim active=%d host=%s guest=%s apk=%s\n",
              g_env_shim.load() ? 1 : 0, g_env_host_pkg.c_str(),
              g_env_guest_pkg.c_str(), g_env_real_apk.c_str());
    return g_env_shim.load() ? JNI_TRUE : JNI_FALSE;
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


