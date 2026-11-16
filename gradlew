#!/usr/bin/env sh
# Gradle Wrapper Launcher for Geely Galaxy Starshine 8 Live Wallpaper
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
  exec "${JAVA_HOME:-/usr}/bin/java" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "ERROR: Neither gradle/wrapper/gradle-wrapper.jar nor system 'gradle' was found. Run 'gradle wrapper --gradle-version 8.7' or open in Android Studio." >&2
  exit 1
fi
