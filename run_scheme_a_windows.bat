@echo off
chcp 65001 >nul
echo ============================================================================
echo   2025款吉利银河星耀8 动态壁纸系统 — 方案A (VS Code + MuMu模拟器12) 一键启动器
echo ============================================================================

echo [1/4] 正在连接 MuMu 模拟器 12 ADB 端口 (127.0.0.1:16384)...
adb connect 127.0.0.1:16384

echo [2/4] 正在编译并安装 Debug APK 至虚拟座舱...
call gradle installDebug
if %errorlevel% neq 0 (
    echo [错误] 编译失败，请检查 JAVA_HOME (需 JDK 17) 与 local.properties 中的 sdk.dir 配置！
    pause
    exit /b 1
)

echo [3/4] 正在映射局域网快传端口 (localhost:8980 -^> 车机:8980)...
adb forward tcp:8980 tcp:8980

echo [4/4] 正在唤醒星耀8 15.4寸 2560x1600 驻车画报与工作台...
adb shell am start -n com.galaxy.starshine8.wallpaper/.host.ImmersiveShowcaseActivity

echo ============================================================================
echo   启动成功！现在可在电脑浏览器访问 http://127.0.0.1:8980/upload 测试传图裁剪
echo ============================================================================
pause
