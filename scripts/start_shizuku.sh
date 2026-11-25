#!/usr/bin/env bash
# 一键启动 Shizuku 服务（等价于在 Shizuku app 的「查看指令」里执行那条 adb 命令）。
#
# 为什么需要它：Shizuku 的授权靠一个以 shell 身份运行的 shizuku_server，手机重启或服务被杀后
# 它不会自己回来。BTControl 的「按 MAC 建 CDM 关联」「单 profile 连接 HFP / LE Audio」都走它，
# 服务不在时 app 只能退回系统选择器或报 profile_not_permitted。
#
# 用法：
#   ./scripts/start_shizuku.sh              # 用默认 adb（PATH 或 ANDROID_HOME）
#   ADB=/path/to/adb ./scripts/start_shizuku.sh
#   ./scripts/start_shizuku.sh -s 9e237109  # 多台设备时指定序列号
#
# 前提：USB 已连（或已开无线调试）、手机装了 Shizuku、系统设置里已开启「无线调试/USB 调试」。

set -euo pipefail

SHIZUKU_PKG="moe.shizuku.privileged.api"
SERIAL=""
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    SERIAL="$2"
fi

# --- 1. 找 adb ---------------------------------------------------------------
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb.exe"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    else
        echo "错误：找不到 adb。请把它加入 PATH，或设置 ADB=/path/to/adb。" >&2
        exit 1
    fi
fi

ADB_CMD=("$ADB")
if [ -n "$SERIAL" ]; then
    ADB_CMD+=(-s "$SERIAL")
fi

run() { "${ADB_CMD[@]}" "$@"; }

# --- 2. 设备 / 包检查 --------------------------------------------------------
# 注意：Windows 上 adb 输出是 CRLF，行尾带 \r，必须 tr -d 掉再匹配。
if ! run devices | tr -d '\r' | grep -qE "[[:space:]]device$"; then
    echo "错误：没有已连接的设备（adb devices 里没有 device 状态的行）。" >&2
    exit 1
fi

INSTALL_PATH="$(run shell pm path "$SHIZUKU_PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1 || true)"
if [ -z "$INSTALL_PATH" ]; then
    echo "错误：手机上没装 Shizuku（$SHIZUKU_PKG）。" >&2
    echo "      装了之后还要在 Shizuku app 内完成配对/启动过一次服务。" >&2
    exit 1
fi

# --- 3. 已经在跑就直接返回 ---------------------------------------------------
if run shell "ps -A 2>/dev/null | grep -q shizuku_server" >/dev/null 2>&1; then
    SERVER_PID="$(run shell "pidof shizuku_server 2>/dev/null || pgrep -f shizuku_server 2>/dev/null" | tr -d '\r' | head -n 1)"
    echo "Shizuku 服务已在运行（pid=${SERVER_PID:-unknown}），无需重复启动。"
    echo "提示：如果 app 里仍显示未授权，去详情页点「关联 CDM」→「请求授权」即可。"
    exit 0
fi

# --- 4. 推导 libshizuku.so 路径 ----------------------------------------------
# pm path 形如 /data/app/~~xxx==/moe.shizuku.privileged.api-yyy==/base.apk
# 原生库在同级 lib/<abi>/libshizuku.so；abi 取 ro.product.cpu.abi（arm64-v8a → arm64）。
APP_DIR="$(printf '%s' "$INSTALL_PATH" | sed 's#/base\.apk$##')"
ABI="$(run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$ABI" in
    arm64-v8a) ABI_DIR="arm64" ;;
    armeabi-v7a|armeabi) ABI_DIR="arm" ;;
    x86_64) ABI_DIR="x86_64" ;;
    x86) ABI_DIR="x86" ;;
    *) ABI_DIR="arm64" ;;
esac
LIB="$APP_DIR/lib/$ABI_DIR/libshizuku.so"

if ! run shell "[ -f '$LIB' ]" >/dev/null 2>&1; then
    echo "错误：找不到 $LIB" >&2
    echo "      实际安装路径：$INSTALL_PATH（abi=$ABI）" >&2
    echo "      可手动执行：adb shell $APP_DIR/lib/$(run shell ls "$APP_DIR/lib" | tr -d '\r' | head -n 1)/libshizuku.so 试一下。" >&2
    exit 1
fi

# --- 5. 启动 ----------------------------------------------------------------
echo "启动 Shizuku：$LIB"
run shell "$LIB" || {
    echo "错误：启动失败。常见原因：未开启 USB 调试/无线调试、或手机端 Shizuku 未完成过一次配对。" >&2
    exit 1
}

# libshizuku.so 会先输出 "info: starting..."，服务进程稍后才出现，等一会儿再确认。
for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if run shell "ps -A 2>/dev/null | grep -q shizuku_server" >/dev/null 2>&1; then
        SERVER_PID="$(run shell "pidof shizuku_server 2>/dev/null || pgrep -f shizuku_server 2>/dev/null" | tr -d '\r' | head -n 1)"
        echo "Shizuku 服务已启动（pid=${SERVER_PID:-unknown}）。"
        echo "下一步：打开 BTControl → 设备详情页 →「关联 CDM」→「请求授权」→ 系统弹窗选「始终允许」。"
        exit 0
    fi
done

echo "错误：命令已执行，但 10 秒内没看到 shizuku_server 进程。请到 Shizuku app 里用「查看指令」核对。" >&2
exit 1
