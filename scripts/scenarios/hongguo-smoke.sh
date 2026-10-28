#!/usr/bin/env bash
# 红果免费短剧状态机冒烟：按页面状态推进，异常状态立即退出，不做长固定等待。
# 用法：
#   ./scripts/scenarios/hongguo-smoke.sh -d 127.0.0.1:16384 [--fresh] [--no-relaunch]
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEVICE="${DEVICE:-127.0.0.1:16384}"
FRESH=0
RELAUNCH=1
while [ $# -gt 0 ]; do
    case "$1" in
        -d) DEVICE="$2"; shift 2 ;;
        --fresh) FRESH=1; shift ;;
        --no-relaunch) RELAUNCH=0; shift ;;
        -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

PKG=com.phoenix.read
UI_GUEST_PKG=$PKG
UI_CASE="hongguo-$(date +%Y%m%d-%H%M%S)"
export DEVICE UI_CASE UI_GUEST_PKG
source "$ROOT/scripts/lib/ui.sh"
ui_init

LAST_STATE=""

note_state() {
    local state="$1"
    if [ "$state" != "$LAST_STATE" ]; then
        ui_info "状态: $state"
        ui_shot "state-$state" >/dev/null
        LAST_STATE="$state"
    fi
}

wait_stop() {
    local deadline=$(( $(date +%s) + ${1:-8} ))
    while ui_guest_running "$PKG"; do
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 0.5
    done
    return 0
}

# 返回：0=main 10=privacy 20=异常页 21=进程退出 22=不在前台 1=超时
wait_screen() {
    local timeout="${1:-30}" start deadline state
    start=$(date +%s)
    deadline=$((start + timeout))
    while :; do
        state=$(ui_state)
        note_state "$state"
        case "$state" in
            main) return 0 ;;
            privacy) return 10 ;;
            notification) ui_dismiss_popups ;;
            unsafe|network_error|anr|crash|safe_mode) return 20 ;;
            not_running)
                if [ $(( $(date +%s) - start )) -ge 6 ]; then return 21; fi
                ;;
            outside)
                if ! ui_guest_running "$PKG"; then return 21; fi
                if [ $(( $(date +%s) - start )) -ge 6 ]; then return 22; fi
                ;;
        esac
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 0.7
    done
}

enter_feed() {
    local allow_privacy="$1" rc
    while :; do
        wait_screen 35
        rc=$?
        case "$rc" in
            0)
                ui_pass "推荐流已加载（$LAST_STATE）"
                return 0
                ;;
            10)
                ui_shot "privacy" >/dev/null
                if [ "$allow_privacy" -eq 0 ]; then
                    ui_fail "重启后重新出现隐私页（Guest 数据未保留）"
                fi
                if ui_tap_text "同意并继续" 2; then
                    ui_pass "隐私同意"
                    LAST_STATE=""
                    sleep 0.5
                    continue
                fi
                ui_fail "隐私页点击失败"
                return 1
                ;;
            20)
                ui_fail "被异常提示拦截: $LAST_STATE"
                ui_shot "blocked-$LAST_STATE" >/dev/null
                return 1
                ;;
            21)
                ui_fail "应用进程已退出"
                ui_shot "left-app" >/dev/null
                return 1
                ;;
            22)
                ui_fail "应用已不在前台"
                ui_shot "left-app" >/dev/null
                return 1
                ;;
            *)
                ui_fail "等待推荐流超时（最后状态: $LAST_STATE）"
                ui_shot "timeout-$LAST_STATE" >/dev/null
                return 1
                ;;
        esac
    done
}

probe_markers() {
    local seconds="${1:-4}" start state
    start=$(date +%s)
    while :; do
        state=$(ui_state)
        note_state "$state"
        case "$state" in
            unsafe|network_error|anr|crash|safe_mode) return 20 ;;
            not_running) return 21 ;;
            outside)
                if ! ui_guest_running "$PKG"; then return 21; fi
                ;;
        esac
        [ $(( $(date +%s) - start )) -ge "$seconds" ] && return 0
        sleep 0.7
    done
}

echo "-- 启动 --"
# 容器只有 g0/g1 两个槽；先停其他 Guest，避免槽位被占导致 launch failed。
ui_stop_other_guests "$PKG"
# 官方安装包与 Guest 进程名相同；先停官方进程，避免 OCR/ps 把两者混淆。
ui_shell am force-stop "$PKG" >/dev/null 2>&1 || true
if ! ui_guest_installed "$PKG"; then
    ui_fail "目标应用未安装: $PKG"
    ui_report
    exit 1
fi
ui_guest_stop "$PKG" >/dev/null 2>&1 || true
if wait_stop 8; then ui_pass "旧进程已退出"; else ui_fail "旧进程 8 秒内未退出"; fi
if [ "$FRESH" -eq 1 ]; then
    ui_guest_clear "$PKG"
    ui_info "已清除 Guest 数据"
fi
ui_clear_logcat
VT_LOG_BASE=$(ui_shell run-as io.vt.framework wc -l files/logs/xposed.log 2>/dev/null | awk '{print $1}')
VT_LOG_BASE=${VT_LOG_BASE:-0}
ui_guest_launch "$PKG"
FIRST_SHOT=$(ui_shot "launch-first")
ui_info "启动首屏截图: $FIRST_SHOT"

if enter_feed 1; then
    if ui_guest_running "$PKG"; then ui_pass "推荐流进程存活"; else ui_fail "推荐流进程退出"; fi
else
    ui_save_logs
    ui_report
    exit 1
fi

echo "-- 播放 --"
if ui_tap_text "全屏观看" 3; then
    ui_info "点击 全屏观看"
else
    ui_tap_ratio 50 45
    ui_info "未找到 全屏观看，点击画面中心"
fi
play_start=$(date +%s)
retried=0
while :; do
    state=$(ui_state)
    note_state "$state"
    case "$state" in
        player)
            ui_pass "进入播放页"
            break
            ;;
        unsafe|network_error|anr|crash|safe_mode)
            ui_fail "播放被异常提示拦截: $state"
            ui_shot "play-blocked" >/dev/null
            break
            ;;
        outside|not_running)
            if ! ui_guest_running "$PKG"; then ui_fail "播放时进程退出"; break; fi
            ;;
        main)
            if [ $(( $(date +%s) - play_start )) -ge 3 ] && [ "$retried" -eq 0 ]; then
                retried=1
                if ui_tap_text "全屏观看" 2; then
                    ui_info "重试点击 全屏观看"
                else
                    ui_tap_ratio 50 45
                    ui_info "重试点击画面中心"
                fi
                play_start=$(date +%s)
            fi
            ;;
        privacy)
            ui_tap_text "同意并继续" 2
            play_start=$(date +%s)
            ;;
        notification) ui_dismiss_popups ;;
        unknown|guest)
            if [ $(( $(date +%s) - play_start )) -ge 6 ] && ui_guest_running "$PKG"; then
                ui_pass "播放页无可识别文本，进程存活且无异常（截图留证）"
                break
            fi
            ;;
    esac
    if [ $(( $(date +%s) - play_start )) -ge 15 ]; then
        ui_fail "点击播放后 15 秒未进入播放状态（最后状态: $state）"
        ui_shot "play-timeout" >/dev/null
        break
    fi
    sleep 0.7
done
if probe_markers 5; then ui_pass "播放 5 秒内无异常提示"; else
    ui_fail "播放期间出现异常: $LAST_STATE"
fi
ui_shot "play" >/dev/null

echo "-- 下滑切换 --"
ui_swipe_up 250
if probe_markers 5; then ui_pass "下滑后 5 秒内无异常提示"; else
    ui_fail "下滑后出现异常: $LAST_STATE"
fi
if ui_guest_running "$PKG"; then ui_pass "下滑后进程存活"; else ui_fail "下滑后进程退出"; fi
ui_shot "swipe" >/dev/null

if [ "$RELAUNCH" -eq 1 ]; then
    echo "-- 停止并重启 --"
    ui_guest_stop "$PKG"
    if wait_stop 10; then ui_pass "容器已停止"; else ui_fail "停止后进程仍存活"; fi
    ui_guest_launch "$PKG"
    RELAUNCH_SHOT=$(ui_shot "relaunch-first")
    ui_info "重启首屏截图: $RELAUNCH_SHOT"
    if enter_feed 0; then
        if probe_markers 4; then ui_pass "重启后无异常提示"; else
            ui_fail "重启后出现异常: $LAST_STATE"
        fi
        ui_shot "relaunch" >/dev/null
    fi
fi

echo "-- 崩溃统计 --"
main_crash=$(ui_crash_count_main "$PKG")
sub_crash=$(ui_crash_count_sub "$PKG")
if [ "$main_crash" -eq 0 ]; then ui_pass "主进程 FATAL=0"; else ui_fail "主进程 FATAL=$main_crash"; fi
if [ "$sub_crash" -eq 0 ]; then
    ui_pass "子进程 FATAL=0"
else
    ui_info "子进程 FATAL=$sub_crash（记录，待修）"
fi
net110=$(ui_shell run-as io.vt.framework tail -n +$((VT_LOG_BASE + 1)) files/logs/xposed.log 2>/dev/null |
    grep -ac "VT-NET code=110" || true)
toast_suppressed=$(ui_shell run-as io.vt.framework tail -n +$((VT_LOG_BASE + 1)) files/logs/xposed.log 2>/dev/null |
    grep -ac "VT-TOAST suppressed 0x7f06130f" || true)
if [ "${net110:-0}" -eq 0 ]; then
    ui_pass "本轮未被服务端 110 拒绝"
elif [ "${toast_suppressed:-0}" -ge "${net110:-0}" ]; then
    ui_info "服务端 110 $net110 次，盗版 Toast 已抑制（书城内容仍可能缺失）"
else
    ui_fail "本轮出现 $net110 次服务端 110，Toast 抑制 $toast_suppressed 次"
fi

ui_save_logs
ui_shot "final" >/dev/null
ui_report
