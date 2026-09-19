#!/bin/sh
# 简化但正确的 Gradle wrapper launcher（POSIX sh）
# 仅在本机/CI 作为 entry，真正的下载与分发由 gradle-wrapper.jar 完成

# 1. 解析 APP_HOME（gradlew 所在目录）
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

# 2. 找 java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVACMD="java"
else
  echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
  echo "       and/or no java found in PATH" >&2
  exit 1
fi

# 3. wrapper jar 路径
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: gradle-wrapper.jar not found at $WRAPPER_JAR" >&2
  exit 1
fi

# 4. JVM 默认参数（最小化，避免 word-splitting 坑）
DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'

# 5. 启动 wrapper main（注意：-Xmx 作为 JVM 参数放 -classpath 之前）
exec "$JAVACMD" \
  -Xmx64m -Xms64m \
  -classpath "$WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
