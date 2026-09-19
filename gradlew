#!/bin/sh
# 简易 gradle 启动器：直接用 gradle-8.5 发行版的 jar 跑
# CI 环境下若没有本地 /home/coomi/gradle-8.5，会自动下载 gradle-wrapper 风格的发行版到 ~/.gradle
if [ -d /home/coomi/gradle-8.5 ]; then
  GRADLE_HOME=/home/coomi/gradle-8.5
else
  GRADLE_HOME=$(mktemp -d)
  curl -fsSL 'https://services.gradle.org/distributions/gradle-8.5-bin.zip' -o "$GRADLE_HOME/gradle.zip"
  unzip -q -o "$GRADLE_HOME/gradle.zip" -d "$GRADLE_HOME/"
  GRADLE_HOME="$GRADLE_HOME/gradle-8.5"
fi
JAVA_HOME=${JAVA_HOME:-/home/coomi/jdk/jdk-17.0.20.1+1}
CL="$GRADLE_HOME/lib/gradle-launcher-8.5.jar"
for j in "$GRADLE_HOME"/lib/*.jar; do CL="$CL:$j"; done
for j in "$GRADLE_HOME"/lib/plugins/*.jar; do CL="$CL:$j"; done
exec "$JAVA_HOME/bin/java" -cp "$CL" org.gradle.launcher.GradleMain "$@"
