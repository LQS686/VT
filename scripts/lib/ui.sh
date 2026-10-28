#!/usr/bin/env bash
# 可复用 adb UI 库：优先用「mCurrentFocus 的 Guest Activity + OCR 文字/坐标」判状态，
# uiautomator 只在静态页手动 dump 时使用（视频页会段错误）。
# 用法：source scripts/lib/ui.sh

: "${ADB:=adb}"
: "${DEVICE:=127.0.0.1:16384}"
: "${UI_ROOT:=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
: "${UI_CASE:=uitest-$(date +%Y%m%d-%H%M%S)}"
: "${UI_ART:=$UI_ROOT/tmp_uitest/$UI_CASE}"
: "${UI_GUEST_PKG:=com.phoenix.read}"
: "${OCR_PYTHON:=python}"

if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
    for ADB_CAND in "/d/AndroidDev/sdk/platform-tools/adb.exe" "D:/AndroidDev/sdk/platform-tools/adb.exe"; do
        if [ -x "$ADB_CAND" ]; then ADB="$ADB_CAND"; break; fi
    done
fi

UI_PASS=0
UI_FAIL=0
UI_W=1080
UI_H=1920
UI_SHOT_SEQ=0
UI_OCR=1
UI_OCR_PNG=""
UI_OCR_TSV=""

ui_adb() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" "$@"; }
ui_shell() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" shell "$@"; }

ui_size() {
    local out w h
    out=$(ui_shell wm size 2>/dev/null | tr -d '\r')
    w=$(printf '%s\n' "$out" | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1/p' | tail -1)
    h=$(printf '%s\n' "$out" | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\2/p' | tail -1)
    if [ -n "${w:-}" ] && [ -n "${h:-}" ]; then UI_W=$w; UI_H=$h; fi
}

ui_init() {
    mkdir -p "$UI_ART"
    UI_OCR_PNG="$UI_ART/ocr.png"
    UI_OCR_TSV="$UI_ART/ocr.tsv"
    ui_size
    UI_HOST_USER=$(ui_shell run-as io.vt.framework id -un 2>/dev/null | tr -d '\r')
    if ! "$OCR_PYTHON" -c 'import rapidocr_onnxruntime' >/dev/null 2>&1; then
        UI_OCR=0
    fi
    echo "== $UI_CASE =="
    echo "device=$DEVICE size=${UI_W}x${UI_H} ocr=$UI_OCR guest=$UI_GUEST_PKG artifacts=$UI_ART"
}

ui_pass() { UI_PASS=$((UI_PASS + 1)); echo "  [PASS] $*"; }
ui_fail() { UI_FAIL=$((UI_FAIL + 1)); echo "  [FAIL] $*"; }
ui_info() { echo "  [INFO] $*"; }

# ---------- OCR ----------

ui_ocr_refresh() {
    [ "$UI_OCR" -eq 1 ] || return 1
    MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" exec-out screencap -p >"$UI_OCR_PNG" 2>/dev/null || return 1
    [ -s "$UI_OCR_PNG" ] || return 1
    PYTHONIOENCODING=utf-8 "$OCR_PYTHON" "$UI_ROOT/scripts/lib/ocr.py" "$UI_OCR_PNG" \
        >"$UI_OCR_TSV" 2>"$UI_ART/ocr.err" || return 1
    return 0
}

ui_ocr_texts() {
    [ -s "$UI_OCR_TSV" ] || return 0
    cut -f3 "$UI_OCR_TSV"
}

ui_ocr_has_in() {
    local text="$1" file="${2:-$UI_OCR_TSV}"
    [ -s "$file" ] || return 1
    awk -F'\t' -v q="$text" 'index($3,q) > 0 { found=1; exit } END { exit !found }' "$file"
}

ui_ocr_find_in() {
    local text="$1" file="${2:-$UI_OCR_TSV}"
    [ -s "$file" ] || return 1
    awk -F'\t' -v q="$text" '
        index($3,q) > 0 {
            n = split($2, b, ",")
            if (n < 4) next
            a = (b[3] - b[1]) * (b[4] - b[2])
            if (best == "" || a < best) { best = a; line = b[1] " " b[2] " " b[3] " " b[4] }
        }
        END { if (line != "") print line }
    ' "$file"
}

ui_ocr_has() {
    ui_ocr_refresh >/dev/null 2>&1 || return 1
    ui_ocr_has_in "$1" "$UI_OCR_TSV"
}

ui_ocr_find() {
    ui_ocr_refresh >/dev/null 2>&1 || return 1
    ui_ocr_find_in "$1" "$UI_OCR_TSV"
}

ui_ocr_tap() {
    local text="$1" timeout="${2:-5}" deadline bounds x1 y1 x2 y2
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        if ui_ocr_find "$text" >"$UI_ART/.bounds" 2>/dev/null; then
            read -r x1 y1 x2 y2 <"$UI_ART/.bounds"
            ui_tap_abs $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
            return 0
        fi
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 0.5
    done
}

# ---------- 页面状态 ----------

ui_focus_component() {
    local line
    line=$(ui_shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)
    printf '%s\n' "$line" | sed -n 's/.*Window{[^ ]* [^ ]* \([^ }]*\).*/\1/p'
}

ui_top_activity() {
    local line
    line=$(ui_shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity")
    printf '%s\n' "$line" | sed -n 's/.*ActivityRecord{[^ ]* [^ ]* \([^ }]*\).*/\1/p'
}

# 优先返回真实 Guest Activity 类名；同一 Activity 上的弹窗再靠 OCR 细分。
# 状态：outside / vt_ui / splash / main / player / content / safe_mode / guest /
#       privacy / notification / unsafe / network_error / crash / not_running / unknown
ui_state() {
    local focus pkg cls t
    focus=$(ui_focus_component)
    if [ -z "$focus" ]; then
        if ui_guest_running "$UI_GUEST_PKG"; then echo unknown; else echo not_running; fi
        return 0
    fi
    pkg=${focus%%/*}
    cls=${focus#*/}
    if [ "$pkg" = "io.vt.framework" ]; then
        echo vt_ui
        return 0
    fi
    t="$UI_OCR_TSV"
    if [ "$pkg" != "$UI_GUEST_PKG" ]; then
        if ui_ocr_refresh >/dev/null 2>&1; then
            if ui_ocr_has_in "屡次停止运行" "$t" ||
                ui_ocr_has_in "没有响应" "$t" ||
                ui_ocr_has_in "关闭应用" "$t"; then
                echo crash
                return 0
            fi
            if ui_ocr_has_in "允许通知" "$t" || ui_ocr_has_in "通知管理" "$t"; then
                echo notification
                return 0
            fi
        fi
        echo outside
        return 0
    fi
    if ui_ocr_refresh >/dev/null 2>&1; then
        if ui_ocr_has_in "当前版本不安全" "$t" || ui_ocr_has_in "安全校验失败" "$t"; then
            # 单帧 Toast 不应让整个流程判死；连续两帧才认定为真实拦截页。
            UI_UNSAFE_STREAK=$(( ${UI_UNSAFE_STREAK:-0} + 1 ))
            if [ "$UI_UNSAFE_STREAK" -ge 2 ]; then echo unsafe; return 0; fi
        else
            UI_UNSAFE_STREAK=0
        fi
        if ui_ocr_has_in "网络出错" "$t" || ui_ocr_has_in "无法连接网络" "$t" ||
            ui_ocr_has_in "请检查网络设置" "$t"; then
            # 播放器首帧偶发网络提示，连续两帧仍存在才判定为异常页。
            UI_NET_STREAK=$(( ${UI_NET_STREAK:-0} + 1 ))
            if [ "$UI_NET_STREAK" -ge 2 ]; then echo network_error; return 0; fi
        else
            UI_NET_STREAK=0
        fi
        if ui_ocr_has_in "应用无响应" "$t" || ui_ocr_has_in "屡次停止运行" "$t" ||
            ui_ocr_has_in "关闭应用" "$t"; then
            echo crash; return 0
        fi
        if ui_ocr_has_in "同意并继续" "$t"; then echo privacy; return 0; fi
        if ui_ocr_has_in "开启消息通知" "$t" || ui_ocr_has_in "暂不开启" "$t"; then
            echo notification; return 0
        fi
    fi
    case "$cls" in
        *SplashActivity*) echo splash ;;
        *ShortSeriesLandActivity*|*FullScreen*|*Player*) echo player ;;
        *MainFragmentActivity*|*MainActivity*) echo main ;;
        *SafeModeActivity*) echo safe_mode ;;
        *Reader*|*Detail*|*Book*) echo content ;;
        *) echo guest ;;
    esac
}

ui_state_shot() {
    local state path
    state=$(ui_state)
    path=$(ui_shot "state-$state")
    printf '%s %s\n' "$state" "$path"
}

# ---------- 节点 dump（仅静态页诊断用，单次尝试） ----------

ui_dump() {
    local out="$UI_ART/ui.xml"
    if command -v timeout >/dev/null 2>&1; then
        MSYS_NO_PATHCONV=1 timeout 12 "$ADB" -s "$DEVICE" shell uiautomator dump /sdcard/vt_ui.xml >/dev/null 2>&1 || true
    else
        ui_shell uiautomator dump /sdcard/vt_ui.xml >/dev/null 2>&1 || true
    fi
    ui_shell cat /sdcard/vt_ui.xml >"$out" 2>/dev/null || return 1
    [ -s "$out" ] || return 1
    printf '%s\n' "$out"
}

ui_bounds_for_text() {
    local text="$1" file="$2"
    tr '<' '\n' <"$file" |
        grep -F -e "text=\"$text\"" -e "content-desc=\"$text\"" |
        sed -n 's/.*bounds="\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]".*/\1 \2 \3 \4/p' |
        awk 'NF==4 { a=($3-$1)*($4-$2); if (a>0 && (best=="" || a<best)) { best=a; line=$0 } } END { print line }'
}

ui_save_ui() {
    local name="${1:-ui}" file
    file=$(ui_dump) || return 1
    cp "$file" "$UI_ART/$name.xml"
    printf '%s\n' "$UI_ART/$name.xml"
}

# ---------- 通用交互 ----------

ui_tap_abs() { ui_shell input tap "$1" "$2" >/dev/null 2>&1; }
ui_tap_ratio() { ui_tap_abs "$((UI_W * $1 / 100))" "$((UI_H * $2 / 100))"; }

ui_press() {
    # 自定义书卡/卡片控件可能忽略瞬时 tap；按住约 0.3 s 再抬起才命中。
    local x="$1" y="$2"
    if ! ui_shell "input motionevent DOWN $x $y; sleep 0.3; input motionevent UP $x $y" >/dev/null 2>&1; then
        ui_tap_abs "$x" "$y"
    fi
}

ui_wait_text() {
    local text="$1" timeout="${2:-10}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        ui_ocr_refresh >/dev/null 2>&1 && ui_ocr_has_in "$text" "$UI_OCR_TSV" && return 0
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 0.5
    done
}

ui_wait_gone() {
    local text="$1" timeout="${2:-10}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        ui_ocr_refresh >/dev/null 2>&1 && ! ui_ocr_has_in "$text" "$UI_OCR_TSV" && return 0
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 0.5
    done
}

ui_has_text() { ui_wait_text "$1" 1; }

ui_tap_text() { ui_ocr_tap "$1" "${2:-5}"; }
ui_tap_if_text() { ui_ocr_tap "$1" 1; }

ui_dismiss_popups() {
    local round text bounds x1 y1 x2 y2 tapped
    for round in 1 2 3; do
        ui_ocr_refresh >/dev/null 2>&1 || return 0
        tapped=0
        for text in "暂不开启" "以后再说" "我知道了" "跳过" "关闭" "知道了" "取消"; do
            if bounds=$(ui_ocr_find_in "$text" "$UI_OCR_TSV"); then
                read -r x1 y1 x2 y2 <<<"$bounds"
                ui_tap_abs $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
                ui_info "关闭弹窗: $text"
                sleep 0.5
                tapped=1
                break
            fi
        done
        [ "$tapped" -eq 0 ] && return 0
    done
}

ui_swipe_abs() { ui_shell input swipe "$1" "$2" "$3" "$4" "${5:-300}" >/dev/null 2>&1; }
ui_swipe_up() { ui_swipe_abs $((UI_W / 2)) $((UI_H * 75 / 100)) $((UI_W / 2)) $((UI_H * 25 / 100)) "${1:-300}"; }
ui_swipe_down() { ui_swipe_abs $((UI_W / 2)) $((UI_H * 25 / 100)) $((UI_W / 2)) $((UI_H * 75 / 100)) "${1:-300}"; }
ui_swipe_left() { ui_swipe_abs $((UI_W * 80 / 100)) $((UI_H / 2)) $((UI_W * 20 / 100)) $((UI_H / 2)) "${1:-300}"; }
ui_swipe_right() { ui_swipe_abs $((UI_W * 20 / 100)) $((UI_H / 2)) $((UI_W * 80 / 100)) $((UI_H / 2)) "${1:-300}"; }

ui_back() { ui_shell input keyevent 4 >/dev/null 2>&1; }
ui_home() { ui_shell input keyevent 3 >/dev/null 2>&1; }

ui_shot() {
    local name="${1:-shot}" path
    UI_SHOT_SEQ=$((UI_SHOT_SEQ + 1))
    path="$UI_ART/$(date +%H%M%S)-$(printf '%02d' "$UI_SHOT_SEQ")-$name.png"
    MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" exec-out screencap -p >"$path"
    printf '%s\n' "$path"
}

# ---------- Guest 控制 ----------

ui_clear_logcat() { ui_adb logcat -c >/dev/null 2>&1 || true; }

ui_crash_count() {
    local pkg="$1" count
    count=$(ui_adb logcat -d -v brief 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -c "Process: $pkg" || true)
    printf '%s\n' "${count:-0}"
}

ui_crash_count_main() {
    local pkg="$1" count
    count=$(ui_adb logcat -d -v brief 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -cF "Process: $pkg, PID:" || true)
    printf '%s\n' "${count:-0}"
}

ui_crash_count_sub() {
    local pkg="$1" count
    count=$(ui_adb logcat -d -v brief 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -cF "Process: $pkg:" || true)
    printf '%s\n' "${count:-0}"
}

ui_save_logs() {
    ui_adb logcat -d -v threadtime >"$UI_ART/logcat.txt" 2>/dev/null || true
    ui_shell run-as io.vt.framework cat files/logs/xposed.log >"$UI_ART/vt-xposed.log" 2>/dev/null || true
    ui_shell dumpsys activity activities >"$UI_ART/activities.txt" 2>/dev/null || true
    ui_shell dumpsys window >"$UI_ART/windows.txt" 2>/dev/null || true
    [ -s "$UI_OCR_TSV" ] && cp "$UI_OCR_TSV" "$UI_ART/ocr-state.tsv"
    ui_info "日志: $UI_ART/logcat.txt, $UI_ART/vt-xposed.log"
}

ui_assert_text() {
    local text="$1" timeout="${2:-5}" desc="${3:-出现文本: $1}"
    if ui_wait_text "$text" "$timeout"; then ui_pass "$desc"; else ui_fail "$desc"; fi
}

ui_assert_no_text() {
    local text="$1" desc="${2:-不出现文本: $1}"
    if ui_has_text "$text"; then ui_fail "$desc"; else ui_pass "$desc"; fi
}

ui_assert_no_markers() {
    local marker found=0
    for marker in "$@"; do
        if ui_has_text "$marker"; then
            ui_fail "命中异常文本: $marker"
            found=1
        fi
    done
    [ "$found" -eq 0 ] && ui_pass "无异常提示"
}

ui_guest_installed() {
    ui_shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$1"
}

ui_guest_launch() {
    local pkg="$1" class="${2:-}" attempt deadline
    ui_ctrl_idle 4 || true
    for attempt in 1 2; do
        if [ -n "$class" ]; then
            ui_shell am start -n io.vt.framework/.DebugControlActivity --es action launch --es pkg "$pkg" --es class "$class" >/dev/null 2>&1
        else
            ui_shell am start -n io.vt.framework/.DebugControlActivity --es action launch --es pkg "$pkg" >/dev/null 2>&1
        fi
        deadline=$(( $(date +%s) + 4 ))
        while [ "$(date +%s)" -lt "$deadline" ]; do
            ui_guest_running "$pkg" && return 0
            sleep 0.3
        done
        ui_ctrl_idle 3 || true
    done
    return 1
}

ui_guest_stop() {
    ui_ctrl_idle 4 || true
    ui_shell am start -n io.vt.framework/.DebugControlActivity --es action stop --es pkg "$1" >/dev/null 2>&1
}

ui_guest_clear() {
    ui_ctrl_idle 4 || true
    ui_shell am start -n io.vt.framework/.DebugControlActivity --es action clear --es pkg "$1" >/dev/null 2>&1
}

ui_ctrl_idle() {
    # 控制 Activity 收尾期间，am start 的新 intent 会被投递到正在 finish 的实例而丢失。
    local deadline=$(( $(date +%s) + ${1:-4} ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if ! ui_shell dumpsys activity activities 2>/dev/null | grep -q "io.vt.framework/.DebugControlActivity"; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

ui_guest_running() {
    local pkg="$1"
    if [ -n "${UI_HOST_USER:-}" ]; then
        # 官方安装包与 Guest 进程名相同，按宿主 UID 区分，避免把官方进程当成 Guest。
        ui_shell ps -A -o USER,PID,NAME 2>/dev/null | tr -d '\r' |
            awk -v u="$UI_HOST_USER" -v p="$pkg" \
                '$1 == u && ($3 == p || index($3, p ":") == 1) { found = 1 } END { exit !found }'
    else
        ui_shell ps -A 2>/dev/null | tr -d '\r' | grep -qE "[[:space:]]$pkg(:[^[:space:]]+)?$"
    fi
}

ui_stop_other_guests() {
    # 容器只有 g0/g1 两个槽；测试某一应用前先停其他 Guest，避免“两个容器进程都在运行其他应用”。
    local keep="$1" p
    for p in com.phoenix.read com.dragon.read com.ss.android.ugc.aweme; do
        [ "$p" = "$keep" ] && continue
        ui_guest_stop "$p" >/dev/null 2>&1 || true
    done
    sleep 0.5
}

ui_report() {
    echo "== 汇总: PASS=$UI_PASS FAIL=$UI_FAIL =="
    printf 'PASS=%s FAIL=%s\n' "$UI_PASS" "$UI_FAIL" >"$UI_ART/result.txt"
    [ "$UI_FAIL" -eq 0 ]
}
