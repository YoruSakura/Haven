#!/usr/bin/env bash
# Build the standalone upstream mosh-client used by Haven's low-latency Mosh
# backend. The executable is packaged as libmosh_client.so so Android extracts
# it into applicationInfo.nativeLibraryDir; Haven executes it in a child PTY
# and does not link it into the app process.
set -euo pipefail

haven_repo_root="$(cd "$(dirname "$0")/.." && pwd)"
haven_api=26
haven_mosh_commit="bc73a26316ede2a79259d859f8ee309b32412420"
haven_mosh_tag="mosh-1.4.0"
haven_rjyo_url="https://github.com/rjyo/mosh-android/releases/download/v1.0.0/mosh-android-libs-v1.0.0.tar.gz"
haven_rjyo_sha256="8a6c88d9d7646d796db0a7f58571564d59b8dcdc7836b0dbf679318a23141005"
haven_work_dir="$haven_repo_root/build/mosh-native"
haven_output_dir="${MOSH_NATIVE_OUTPUT:-$haven_repo_root/core/mosh/src/main/jniLibs}"

haven_abis=("$@")
if [ "${#haven_abis[@]}" -eq 0 ]; then
    haven_abis=(arm64-v8a x86_64 armeabi-v7a)
fi

haven_ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}"
if [ -z "$haven_ndk" ]; then
    haven_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [ -n "$haven_sdk" ] && [ -d "$haven_sdk/ndk/29.0.14206865" ]; then
        haven_ndk="$haven_sdk/ndk/29.0.14206865"
    fi
fi
if [ -z "$haven_ndk" ] || [ ! -d "$haven_ndk" ]; then
    echo "Android NDK r29 was not found. Set ANDROID_NDK_ROOT to 29.0.14206865." >&2
    exit 1
fi

haven_host_tag="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
haven_toolchain="$haven_ndk/toolchains/llvm/prebuilt/$haven_host_tag"
if [ ! -x "$haven_toolchain/bin/llvm-strip" ]; then
    echo "Unsupported or incomplete Android NDK: $haven_ndk" >&2
    exit 1
fi

mkdir -p "$haven_work_dir/downloads" "$haven_output_dir"
haven_rjyo_archive="$haven_work_dir/downloads/mosh-android-libs-v1.0.0.tar.gz"
if [ ! -f "$haven_rjyo_archive" ]; then
    echo "Downloading pinned mosh-android v1.0.0 libraries..."
    curl -fL --retry 3 "$haven_rjyo_url" -o "$haven_rjyo_archive.part"
    mv "$haven_rjyo_archive.part" "$haven_rjyo_archive"
fi
echo "$haven_rjyo_sha256  $haven_rjyo_archive" | sha256sum -c -

haven_rjyo_root="$haven_work_dir/rjyo"
haven_rjyo_libs="$haven_rjyo_root/android-libs"
if [ ! -d "$haven_rjyo_libs" ]; then
    mkdir -p "$haven_rjyo_root"
    tar --warning=no-unknown-keyword -xzf "$haven_rjyo_archive" -C "$haven_rjyo_root"
fi

haven_mosh_source="$haven_work_dir/mosh-src"
if [ ! -d "$haven_mosh_source/.git" ]; then
    git clone --filter=blob:none --no-checkout https://github.com/mobile-shell/mosh.git "$haven_mosh_source"
    git -C "$haven_mosh_source" fetch --depth=1 origin "$haven_mosh_commit"
    git -C "$haven_mosh_source" checkout --detach "$haven_mosh_commit"
fi
haven_actual_commit="$(git -C "$haven_mosh_source" rev-parse HEAD)"
if [ "$haven_actual_commit" != "$haven_mosh_commit" ]; then
    echo "Pinned mosh source mismatch: expected $haven_mosh_commit, found $haven_actual_commit" >&2
    exit 1
fi

# rjyo's archive contains the Android-patched generated headers that match its
# static libraries. Overlay only matching upstream headers, then provide the
# generated protobuf/config files required by the standalone frontend build.
haven_include_dir="$haven_rjyo_libs/include"
for haven_header in "$haven_include_dir"/*.h; do
    haven_header_name="$(basename "$haven_header")"
    haven_header_target="$(find "$haven_mosh_source/src" -name "$haven_header_name" -print -quit 2>/dev/null || true)"
    if [ -n "$haven_header_target" ]; then
        cp -f "$haven_header" "$haven_header_target"
    fi
done
cp -f "$haven_include_dir/config.h" "$haven_mosh_source/src/include/config.h"
cp -f "$haven_include_dir/version.h" "$haven_mosh_source/src/frontend/version.h"
cp -f "$haven_include_dir"/hostinput.pb.h \
    "$haven_include_dir"/transportinstruction.pb.h \
    "$haven_include_dir"/userinput.pb.h \
    "$haven_mosh_source/src/protobufs/"

haven_prelude="$haven_work_dir/prelude.h"
printf '%s\n' \
    '#pragma once' \
    '#include <string>' \
    '#include <vector>' \
    '#include <list>' \
    '#include <map>' \
    '#include <deque>' \
    'using namespace std;' > "$haven_prelude"

for haven_abi in "${haven_abis[@]}"; do
    case "$haven_abi" in
        arm64-v8a) haven_triple="aarch64-linux-android" ;;
        armeabi-v7a) haven_triple="armv7a-linux-androideabi" ;;
        x86_64) haven_triple="x86_64-linux-android" ;;
        *) echo "Unsupported ABI: $haven_abi" >&2; exit 1 ;;
    esac

    haven_cxx="$haven_toolchain/bin/${haven_triple}${haven_api}-clang++"
    haven_cc="$haven_toolchain/bin/${haven_triple}${haven_api}-clang"
    haven_lib_dir="$haven_rjyo_libs/static/$haven_abi"
    haven_frontend="$haven_mosh_source/src/frontend"
    haven_abi_output="$haven_output_dir/$haven_abi"
    mkdir -p "$haven_abi_output"

    # The v1.0.0 x86_64/armv7 ncurses archives reference the optional
    # compiled-in terminfo fallback without containing its definition. Haven
    # always supplies an external TERMINFO database, so a null fallback is the
    # correct implementation. arm64 already contains the real symbol and does
    # not link this compatibility object.
    haven_ncurses_fallback=()
    if ! "$haven_toolchain/bin/llvm-nm" "$haven_lib_dir/libncursesw.a" | \
        grep -qE ' [TW] _nc_fallback2$'; then
        haven_fallback_object="$haven_work_dir/ncurses-fallback-$haven_abi.o"
        "$haven_cc" -O2 -fPIC -c \
            "$haven_repo_root/scripts/native/mosh-ncurses-fallback.c" \
            -o "$haven_fallback_object"
        haven_ncurses_fallback=("$haven_fallback_object")
    fi

    echo "Building upstream $haven_mosh_tag for $haven_abi..."
    "$haven_cxx" \
        -std=c++17 -O2 -fPIE -pie -fexceptions -frtti -DHAVE_CONFIG_H \
        -Wno-deprecated-declarations -include "$haven_prelude" \
        -I"$haven_include_dir" -I"$haven_include_dir/ncursesw" -I"$haven_mosh_source" \
        -I"$haven_frontend" -I"$haven_mosh_source/src/terminal" \
        -I"$haven_mosh_source/src/network" -I"$haven_mosh_source/src/crypto" \
        -I"$haven_mosh_source/src/statesync" -I"$haven_mosh_source/src/util" \
        -I"$haven_mosh_source/src/protobufs" -I"$haven_mosh_source/src/include" \
        "$haven_frontend/mosh-client.cc" \
        "$haven_frontend/stmclient.cc" \
        "$haven_frontend/terminaloverlay.cc" \
        -o "$haven_abi_output/libmosh_client.so" \
        -Wl,-z,max-page-size=16384 -Wl,--start-group \
        "${haven_ncurses_fallback[@]}" \
        "$haven_lib_dir/libmoshnetwork.a" \
        "$haven_lib_dir/libmoshstatesync.a" \
        "$haven_lib_dir/libmoshterminal.a" \
        "$haven_lib_dir/libmoshcrypto.a" \
        "$haven_lib_dir/libmoshutil.a" \
        "$haven_lib_dir/libmoshprotos.a" \
        "$haven_lib_dir/libprotobuf.a" \
        "$haven_lib_dir"/libabsl_*.a \
        "$haven_lib_dir"/libutf8_*.a \
        "$haven_lib_dir/libssl.a" \
        "$haven_lib_dir/libcrypto.a" \
        "$haven_lib_dir/libncursesw.a" \
        -Wl,--end-group -static-libstdc++ -llog -lz -lm
    "$haven_toolchain/bin/llvm-strip" "$haven_abi_output/libmosh_client.so"
    file "$haven_abi_output/libmosh_client.so"
done

echo "Native mosh-client outputs: $haven_output_dir"
