#!/usr/bin/env bash
# adb UI 命令行：文本定位/点击/滑动/等待/截图，便于反复手工与脚本化测试。
# 示例：
#   ./scripts/ui.sh -d 127.0.0.1:16384 launch com.phoenix.read
#   ./scripts/ui.sh wait 推荐 20 && ./scripts/ui.sh tap-text 全屏观看
#   ./scripts/ui.sh up && ./scripts/ui.sh shot after-swipe
set -uo pipefail

ACTION=""
while getopts ":d:c:a:p:" opt; do
    case "$opt" in
        d) DEVICE="$OPTARG" ;;
        c) UI_CASE="$OPTARG" ;;
        a) UI_ART="$OPTARG" ;;
        p) UI_GUEST_PKG="$OPTARG" ;;
        *) echo "usage: $0 [-d device] [-c case] [-a artifacts] [-p guest-pkg] <command> [args]" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))

if [ $# -eq 0 ]; then
    sed -n '2,10p' "$0"
    exit 2
fi

DEVICE="${DEVICE:-127.0.0.1:16384}"
UI_CASE="${UI_CASE:-adhoc-$(date +%Y%m%d)}"
UI_GUEST_PKG="${UI_GUEST_PKG:-com.phoenix.read}"
export DEVICE UI_CASE UI_GUEST_PKG

source "$(dirname "$0")/lib/ui.sh"
if [ -n "${UI_ART:-}" ]; then export UI_ART; fi
ui_init >/dev/null

cmd="$1"
shift

case "$cmd" in
    devices) MSYS_NO_PATHCONV=1 "$ADB" devices -l ;;
    init) ;;
    dump) ui_save_ui "${1:-ui}" ;;
    find) ui_find_text "$1" "${2:-3}" ;;
    wait) ui_wait_text "$1" "${2:-10}" ;;
    wait-gone) ui_wait_gone "$1" "${2:-10}" ;;
    tap-text) ui_tap_text "$1" "${2:-5}" ;;
    tap) ui_tap_abs "$1" "$2" ;;
    ratio) ui_tap_ratio "$1" "$2" ;;
    swipe) ui_swipe_abs "$1" "$2" "$3" "$4" "${5:-300}" ;;
    up) ui_swipe_up "${1:-300}" ;;
    down) ui_swipe_down "${1:-300}" ;;
    left) ui_swipe_left "${1:-300}" ;;
    right) ui_swipe_right "${1:-300}" ;;
    back) ui_back ;;
    home) ui_home ;;
    shot) ui_shot "${1:-shot}" ;;
    dismiss) ui_dismiss_popups ;;
    launch) ui_guest_launch "$1" "${2:-}" ;;
    stop) ui_guest_stop "$1" ;;
    clear) ui_guest_clear "$1" ;;
    running) ui_guest_running "$1" && echo true || echo false ;;
    installed) ui_guest_installed "$1" && echo true || echo false ;;
    state) ui_state ;;
    top) ui_top_activity ;;
    state-shot) ui_state_shot ;;
    crash) ui_crash_count "$1" ;;
    logs) ui_save_logs ;;
    vtlog) ui_shell run-as io.vt.framework tail -n "${1:-60}" files/logs/xposed.log ;;
    texts)
        ui_ocr_refresh >/dev/null || exit 1
        ui_ocr_texts
        ;;
    ocr) ui_ocr_refresh && cat "$UI_OCR_TSV" ;;
    ocr-find) ui_ocr_find "$1" ;;
    ocr-tap) ui_ocr_tap "$1" "${2:-5}" ;;
    foreground) ui_shell dumpsys activity activities | grep -m1 topResumedActivity ;;
    focus) ui_focus_component ;;
    sh) ui_shell "$@" ;;
    *)
        echo "unknown command: $cmd" >&2
        exit 2
        ;;
esac
