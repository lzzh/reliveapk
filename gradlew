#!/bin/sh
# Standard Gradle wrapper launcher (no-wrapper-jar fallback). Uses gradle-wrapper.jar if present.
DIRNAME=$(cd "$(dirname "$0")" && pwd)
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
