#!/usr/bin/env bash
# PackageManager 视图自洽性完整回归。
#
# 覆盖三个场景：
#   A. 容器模式（Guest APK 只在容器内，真机已卸载）—— 最容易暴露问题的场景
#   B. 普通模式（应用真机直装，不经容器）—— 校验审计断言本身符合真机语义
#   C. 容器模式（应用真机已安装）—— 校验列表/直接查询一致性
#
# 每个场景检查：
#   - PM_AUDIT / PM_COMPONENT_AUDIT 全部 PASS（当前共 28 项：基础 17 + 组件 11）
#   - 无 FATAL EXCEPTION
#   - Xposed 模块注入成功
#
# 用法：
#   scripts/pm-regression.sh                 # 用第一台设备
#   scripts/pm-regression.sh emulator-5554   # 指定设备
#   ANDROID_SDK=/path/to/sdk scripts/pm-regression.sh

set -uo pipefail

SDK="${ANDROID_SDK:-D:/AndroidDev/sdk}"
ADB="$SDK/platform-tools/adb.exe"
DEVICE="${1:-}"
HOST_PKG="io.vt.framework"
GUEST_PKG="io.vt.sampleapp"
MODULE_PKG="io.vt.samplemodule"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -x "$ADB" ] && [ ! -f "$ADB" ]; then
    echo "找不到 adb: $ADB" >&2
    echo "请设置 ANDROID_SDK 环境变量" >&2
    exit 2
fi

if [ -z "$DEVICE" ]; then
    DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$DEVICE" ]; then
    echo "没有可用设备，请连接设备或用参数指定 serial" >&2
    exit 2
fi

adb_() { "$ADB" -s "$DEVICE" "$@"; }

PASS=0
FAIL=0

# audit <场景名>：读取 logcat 中的审计结果
audit() {
    local label="$1"
    local out
    out=$(adb_ logcat -d -s VTSample:V | grep -E "PM_AUDIT passed|PM_COMPONENT_AUDIT (passed|FAIL)")
    if [ -z "$out" ]; then
        echo "  [FAIL] $label: 没有采集到审计输出（应用可能未启动）"
        FAIL=$((FAIL + 1))
        return
    fi
    echo "$out" | sed 's/^/    /'

    local summary
    summary=$(echo "$out" | grep -E "PM_AUDIT passed|PM_COMPONENT_AUDIT passed")
    local total_passed=0
    local any_fail=0
    local base_count=0
    local comp_count=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        # "PM_AUDIT passed=17 failed=0" / "PM_COMPONENT_AUDIT passed=11 failed=0"
        # 审计在 onCreate 与 onResume 各跑一次，logcat 会出现两轮，
        # 这里取每类的最大值而不是累加，否则断言总数会翻倍显示。
        # 注意 logcat 行以时间戳开头，不能用前缀匹配。
        local n
        n=$(echo "$line" | sed -n 's/.*passed=\([0-9]\+\).*/\1/p')
        case "$line" in
            *"PM_COMPONENT_AUDIT passed="*) [ "${n:-0}" -gt "$comp_count" ] && comp_count=$n ;;
            *"PM_AUDIT passed="*) [ "${n:-0}" -gt "$base_count" ] && base_count=$n ;;
        esac
        case "$line" in
            *"failed=0"*) total_passed=$((total_passed + 1)) ;;
            *) any_fail=1 ;;
        esac
    done <<< "$summary"
    if [ "$any_fail" -ne 0 ]; then
        echo "  [FAIL] $label: 存在 failed>0 的断言"
        FAIL=$((FAIL + 1))
        return
    fi
    if [ "$total_passed" -lt 2 ]; then
        echo "  [FAIL] $label: 审计输出不完整"
        FAIL=$((FAIL + 1))
        return
    fi
    echo "  [PASS] $label: $((base_count + comp_count)) 项断言全部通过（基础 $base_count + 组件 $comp_count）"
    PASS=$((PASS + 1))
}

fatal_count() {
    local n
    n=$(adb_ logcat -d | grep -c "FATAL EXCEPTION" || true)
    echo "${n:-0}"
}

module_ok() {
    # 模块注入日志可能只在容器自己的 xposed.log 里：
    # 部分厂商 ROM（如 HyperOS）会过滤第三方 tag 的 logcat 输出。
    local out
    out=$(container_log | grep -E "handleLoadPackage 完成|模块.*已加载")
    if [ -n "$out" ]; then
        echo "  [PASS] Xposed 模块注入成功"
        PASS=$((PASS + 1))
    else
        echo "  [FAIL] Xposed 模块未注入"
        FAIL=$((FAIL + 1))
    fi
}

# 读取容器自身的 xposed.log（run-as 需要 debuggable 包）
container_log() {
    adb_ shell run-as "$HOST_PKG" tail -300 files/logs/xposed.log 2>/dev/null
}

build() {
    echo "== 构建 =="
    # Gradle 需要 JDK；优先用环境变量，其次探测常见安装位置
    if [ -z "${JAVA_HOME:-}" ]; then
        local candidate
        for candidate in "D:/AndroidDev/jdk" "/c/Program Files/Android/Android Studio/jbr" \
                         "/c/Program Files/Java/jdk-17"; do
            if [ -x "$candidate/bin/javac" ] || [ -x "$candidate/bin/java.exe" ]; then
                export JAVA_HOME="$candidate"
                break
            fi
        done
    fi
    if [ -n "${JAVA_HOME:-}" ]; then
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "  JAVA_HOME=$JAVA_HOME"
    else
        echo "  [WARN] 未找到 JAVA_HOME，依赖系统 PATH 中的 java"
    fi
    ( cd "$ROOT" && ./gradlew :app:assembleDebug :sample-app:assembleDebug \
        :sample-module:assembleDebug --console=plain ) || {
        echo "构建失败" >&2
        exit 1
    }
}

install_all() {
    local abi
    abi=$(adb_ shell getprop ro.product.cpu.abi | tr -d '\r')
    local apk
    case "$abi" in
        x86_64) apk="$ROOT/app/build/outputs/apk/debug/app-x86_64-debug.apk" ;;
        arm64-v8a) apk="$ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk" ;;
        armeabi-v7a) apk="$ROOT/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk" ;;
        *) apk="$ROOT/app/build/outputs/apk/debug/app-x86_64-debug.apk" ;;
    esac
    adb_ install -r -t "$apk" > /dev/null || { echo "安装宿主失败" >&2; exit 1; }
    adb_ install -r -t "$ROOT/sample-app/build/outputs/apk/debug/sample-app-debug.apk" > /dev/null \
        || { echo "安装示例应用失败" >&2; exit 1; }
    adb_ install -r -t "$ROOT/sample-module/build/outputs/apk/debug/sample-module-debug.apk" > /dev/null \
        || { echo "安装示例模块失败" >&2; exit 1; }
}

import_guest() {
    adb_ shell am force-stop "$HOST_PKG" > /dev/null 2>&1
    sleep 1
    adb_ shell am start -n "$HOST_PKG/.DebugControlActivity" \
        --es action import --es pkg "$GUEST_PKG" > /dev/null
    sleep 4
    enable_module
}

# 启用示例模块。
# import 是异步落库的：enable 若发生在 import 完成之前，会写到旧记录上，
# 随后被 import 覆盖，表现为"未启用任何 Xposed 模块"。这里重试直到生效。
enable_module() {
    local i
    for i in 1 2 3 4 5; do
        adb_ shell am start -n "$HOST_PKG/.DebugControlActivity" \
            --es action enable --es pkg "$GUEST_PKG" --es module "$MODULE_PKG" > /dev/null
        sleep 2
        local enabled
        enabled=$(adb_ shell run-as "$HOST_PKG" cat files/vt_guests.json 2>/dev/null \
            | tr ',' '\n' | grep -c "\"$MODULE_PKG\"")
        if [ "${enabled:-0}" -gt 0 ]; then
            echo "  模块已启用（尝试 $i 次）"
            return 0
        fi
    done
    echo "  [WARN] 模块启用未生效，模块注入检查可能失败"
    return 1
}

launch_guest() {
    adb_ shell am force-stop "$HOST_PKG" > /dev/null 2>&1
    sleep 1
    # 清空容器日志，避免上一场景的记录造成误判
    adb_ shell run-as "$HOST_PKG" sh -c ": > files/logs/xposed.log" > /dev/null 2>&1
    adb_ logcat -c
    adb_ shell am start -n "$HOST_PKG/.DebugControlActivity" \
        --es action launch --es pkg "$GUEST_PKG" > /dev/null
    sleep 8
}

echo "设备: $DEVICE"
build
install_all

echo
echo "== 场景 C：容器模式（应用真机已安装）=="
import_guest
launch_guest
audit "容器模式/已安装"
[ "$(fatal_count)" = "0" ] && { echo "  [PASS] 无 FATAL EXCEPTION"; PASS=$((PASS + 1)); } \
                        || { echo "  [FAIL] 存在 FATAL EXCEPTION"; FAIL=$((FAIL + 1)); }
module_ok

echo
echo "== 场景 A：容器模式（真机已卸载该应用）=="
adb_ shell pm uninstall "$GUEST_PKG" > /dev/null 2>&1
sleep 1
launch_guest
audit "容器模式/已卸载"
[ "$(fatal_count)" = "0" ] && { echo "  [PASS] 无 FATAL EXCEPTION"; PASS=$((PASS + 1)); } \
                        || { echo "  [FAIL] 存在 FATAL EXCEPTION"; FAIL=$((FAIL + 1)); }
module_ok

echo
echo "== 场景 B：普通模式（应用真机直装）=="
adb_ install -r -t "$ROOT/sample-app/build/outputs/apk/debug/sample-app-debug.apk" > /dev/null
sleep 2
adb_ logcat -c
adb_ shell am start -n "$GUEST_PKG/.MainActivity" > /dev/null
sleep 6
audit "普通模式/真机直装"

echo
echo "== 汇总 =="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
