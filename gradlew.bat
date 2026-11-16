@echo off
@rem Gradle Wrapper Launcher for Geely Galaxy Starshine 8 Live Wallpaper
setlocal
set DIRNAME=%~dp0
set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

if exist "%WRAPPER_JAR%" (
    java.exe -Xmx64m -Xms64m -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
    exit /b %ERRORLEVEL%
)

where gradle >nul 2>nul
if %ERRORLEVEL% equ 0 (
    gradle %*
    exit /b %ERRORLEVEL%
)

echo ERROR: Neither gradle\wrapper\gradle-wrapper.jar nor system 'gradle' found. Open in Android Studio or run 'gradle wrapper --gradle-version 8.7'.
exit /b 1
