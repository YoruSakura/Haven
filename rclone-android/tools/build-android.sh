#!/usr/bin/env bash
# Build only the five retained userspace VPN bridges.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
export PATH="/usr/local/go/bin:${GOPATH:-$HOME/go}/bin:$PATH"
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-mod=mod -buildvcs=false -ldflags=-checklinkname=0"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is required}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:?Android NDK is required}"
case "${TARGET_ABI:-all}" in
    arm64|arm64-v8a) TARGETS=android/arm64 ;;
    x64|x86_64) TARGETS=android/amd64 ;;
    armv7|armeabi-v7a) TARGETS=android/arm ;;
    all) TARGETS=android/arm64,android/amd64,android/arm ;;
    *) echo "Unsupported TARGET_ABI" >&2; exit 1 ;;
esac
mkdir -p "$PROJECT_DIR/build"
cd "$PROJECT_DIR/go"
gomobile init
gomobile bind -target="$TARGETS" -javapkg=sh.haven.rclone.binding -androidapi=26 \
    -o "$PROJECT_DIR/build/rcbridge.aar" \
    ./wgbridge ./tsbridge ./nbbridge ./ovpnbridge ./ocbridge
# Replace old generated output so removed cloud/mail classes cannot survive.
rm -rf "$PROJECT_DIR/build/extracted" "$PROJECT_DIR/jniLibs"
unzip -oq "$PROJECT_DIR/build/rcbridge.aar" 'jni/*' classes.jar -d "$PROJECT_DIR/build/extracted"
mkdir -p "$PROJECT_DIR/jniLibs"
cp -R "$PROJECT_DIR/build/extracted/jni/." "$PROJECT_DIR/jniLibs/"
cp "$PROJECT_DIR/build/extracted/classes.jar" "$PROJECT_DIR/build/rcbridge-bindings.jar"
LLVM_STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for lib in "$PROJECT_DIR"/jniLibs/*/libgojni.so; do
    "$LLVM_STRIP" --strip-debug "$lib"
done
