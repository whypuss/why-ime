#!/usr/bin/env bash
set -e

# ========================================================
# 歪輸入法（Wai IME）Android APK 一鍵建置腳本
# ========================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/homebrew/Caskroom/android-ndk/29/AndroidNDK14206865.app/Contents/NDK}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

echo "=== [1/3] 編譯 Rust JNI 核心層 (arm64-v8a, x86_64) ==="
cd "${REPO_ROOT}"

cargo ndk -t arm64-v8a -o crates/wai-android/jniLibs build -p wai-android --release
cargo ndk -t x86_64 -o crates/wai-android/jniLibs build -p wai-android --release

echo "=== [2/3] 同步 jniLibs 到 Android 專案 ==="
mkdir -p "${SCRIPT_DIR}/app/src/main/jniLibs"
cp -r crates/wai-android/jniLibs/* "${SCRIPT_DIR}/app/src/main/jniLibs/"

echo "=== [3/3] 建置 Android APK ==="
cd "${SCRIPT_DIR}"
./gradlew assembleDebug

APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "✅ APK 建置成功！"
echo "📦 輸出位置: ${APK_PATH}"
ls -lh "${APK_PATH}"
