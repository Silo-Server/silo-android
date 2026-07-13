#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="2.3.1"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/26.3.11579264}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
WORK="$ROOT/android-shared/build/dovi-aar"
OUT="$ROOT/android-shared/libs/silo-dovi-bridge-$VERSION.aar"
SOURCE="$ROOT/android-shared/src/native/dovi/silo_dovi.cpp"
BASE="https://github.com/edde746/libdovi-builds/releases/download/v$VERSION"

if [[ ! -x "$TOOLCHAIN/bin/clang++" ]]; then
  echo "Android NDK toolchain not found: $TOOLCHAIN" >&2
  exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK/aar/jni"
mkdir -p "$WORK/aar/META-INF"
printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.siloserver.silo.dovi" />\n' > "$WORK/aar/AndroidManifest.xml"
cp "$ROOT/android-shared/src/native/dovi/THIRD_PARTY_NOTICES.txt" \
  "$WORK/aar/META-INF/SILO_DOVI_NOTICES.txt"

build_abi() {
  local abi="$1" triple="$2" target="$3" checksum="$4"
  local archive="$WORK/libdovi-$triple.tar.gz"
  local libdir="$WORK/$abi"
  curl -fsSL "$BASE/libdovi-$triple.tar.gz" -o "$archive"
  printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 -c -
  mkdir -p "$libdir" "$WORK/aar/jni/$abi"
  tar -xzf "$archive" -C "$libdir"
  "$TOOLCHAIN/bin/clang++" \
    --target="$target" --sysroot="$TOOLCHAIN/sysroot" \
    -std=c++17 -O2 -fPIC -fvisibility=hidden -shared -static-libstdc++ \
    -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
    "$SOURCE" "$libdir/libdovi.a" -llog -ldl -lm \
    -o "$WORK/aar/jni/$abi/libsilo_dovi.so"
  "$TOOLCHAIN/bin/llvm-strip" "$WORK/aar/jni/$abi/libsilo_dovi.so"
  if "$TOOLCHAIN/bin/llvm-readelf" -d "$WORK/aar/jni/$abi/libsilo_dovi.so" | grep -q 'libc++_shared.so'; then
    echo "Unexpected libc++_shared.so dependency for $abi" >&2
    exit 1
  fi
}

build_abi arm64-v8a aarch64-linux-android aarch64-linux-android24 \
  9d2983fc86f2f9e6da54c3c84ba8ea3a528690619f312ff4620198071b84e9ae
build_abi armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi24 \
  ed6fec8bf744e41c661b97f5fc4bf1197ebe9b09a140cbde369728e790ee3a68
build_abi x86_64 x86_64-linux-android x86_64-linux-android24 \
  eba59678f89b792f5c6f802962e237542fe8328f6aa03a0a90ee77353dac3194

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$WORK/aar" && zip -q -r "$OUT" AndroidManifest.xml jni META-INF)
echo "Built $OUT"
