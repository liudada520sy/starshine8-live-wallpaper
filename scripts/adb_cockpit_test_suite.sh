#!/usr/bin/env bash
# 2025款吉利银河星耀8 动态壁纸系统 — ADB 实机/模拟器全链路测试脚本
set -e

PKG="com.galaxy.starshine8.wallpaper"
ACT="com.galaxy.starshine8.wallpaper.host.ImmersiveShowcaseActivity"

echo "[1/6] 启动全屏驻车画报与 15.4寸 2560x1600 工作台宿主..."
adb shell am start -n "$PKG/$ACT"
sleep 2

echo "[2/6] 建立 ADB 端口转发 (localhost:8980 -> 车机:8980)，测试免U盘快传心跳探针..."
adb forward tcp:8980 tcp:8980
curl -s http://127.0.0.1:8980/api/v1/ping && echo ""

echo "[3/6] 模拟车辆驶入隧道/夜间开启大灯 (触发 UiModeManager 800ms 35% 柔光压暗 u_NightModeDimming=0.65)..."
adb shell cmd uimode night yes
sleep 3

echo "[4/6] 模拟车辆驶出隧道恢复日间模式 (恢复 100% 亮度)..."
adb shell cmd uimode night no
sleep 2

echo "[5/6] 模拟夏季暴晒座舱温控阶梯 (MODERATE -> 30fps 单层粒子；NONE -> 恢复 60fps 满血)..."
adb shell cmd thermalservice override-status 2
sleep 3
adb shell cmd thermalservice reset

echo "[6/6] 模拟车机 STR (Suspend-to-RAM) 休眠后唤醒自愈..."
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_MENU
echo "全部 ADB 车规工况测试指令执行完毕！"
