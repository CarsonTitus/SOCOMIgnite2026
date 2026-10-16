#!/usr/bin/env bash
# Builds librealsense's Android AAR from source into app/libs/ (no prebuilt AAR is published for 2.58.x).
# Needs JDK 17, Android SDK platform 35, NDK 28.0.13004108 and CMake 3.22.1 (see README "Android recorder").
set -euo pipefail
VERSION=v2.58.4                      # keep in step with the apt SDK / pyrealsense2 on the PC
SRC=${LIBRS_SRC:-$HOME/Android/librealsense-${VERSION#v}}
GRADLE=${GRADLE:-$HOME/Android/gradle-8.11.1/bin/gradle}   # wrappers/android ships no gradle wrapper jar
export JAVA_HOME=${JAVA_HOME:-$(ls -d $HOME/Android/jdk-17* | head -1)}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
HERE=$(cd "$(dirname "$0")" && pwd)

[ -d "$SRC" ] || git clone --depth 1 --branch "$VERSION" https://github.com/IntelRealSense/librealsense.git "$SRC"
cd "$SRC/wrappers/android"
# 2.58.4 bug: Option.java ends OPTION_SAFETY_MODE(96) with ';' before one more constant, so javac fails
sed -i 's/OPTION_SAFETY_MODE(96);/OPTION_SAFETY_MODE(96),/' librealsense/src/main/java/com/intel/realsense/librealsense/Option.java
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$GRADLE" --no-daemon -Dorg.gradle.jvmargs=-Xmx4g :librealsense:assembleRelease
cp librealsense/build/outputs/aar/librealsense-release.aar "$HERE/app/libs/"
echo "Copied librealsense-release.aar to $HERE/app/libs/"
