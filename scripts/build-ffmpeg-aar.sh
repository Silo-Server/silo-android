#!/usr/bin/env bash
#
# build-ffmpeg-aar.sh — Produce media3-decoder-ffmpeg-1.11.0.aar from source.
#
# Output: android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar
#
# Why this script exists:
#   Google never publishes the Media3 decoder_ffmpeg extension as a binary
#   artifact (FFmpeg LGPL distribution compliance). If we want FFmpeg audio
#   decoders in our Media3 renderer graph, we cross-compile FFmpeg against
#   the Android NDK and assemble the AAR ourselves. This is a one-time cost
#   per Media3 bump; the produced AAR is checked into
#   android-shared/libs/ and consumed as a local file dependency.
#
# Build design:
#   * Pinned to Media3 tag `1.11.0` and FFmpeg tag `n6.0`. The
#     libraries/decoder_ffmpeg/README.md in Media3 1.11.0 recommends
#     FFmpeg 6.0 specifically — n7.x has ABI changes that break the JNI
#     glue.
#   * LGPL-only. No --enable-gpl. Every codec we want is in the LGPL
#     subset of FFmpeg.
#   * Decoders restricted to the minimum set covering the codecs the
#     client advertises via PlaybackCapabilityDetector:
#       ac3 eac3 mlp truehd dca alac
#     (eac3 decoder handles E-AC-3 JOC natively; dca decoder handles DTS
#     core + HRA + MA; mlp + truehd together cover TrueHD. vorbis/opus/
#     flac are already decoded by AOSP so we don't ship them. ALAC is included
#     because Media3 1.11 added Matroska ALAC extraction but Android does not
#     guarantee a platform ALAC decoder. ac4 is
#     skipped per the v1 plan — rare in our library, saves ~400 KB/ABI.)
#   * ABIs: arm64-v8a, armeabi-v7a, x86_64. 32-bit x86 is skipped — no
#     target device ships it, and our emulator images are x86_64. The
#     upstream build_ffmpeg.sh builds all four in one pass; we patch out
#     the x86 block before running to save ~3 min + ~2 MB of repo bloat.
#   * The final JNI shared libraries are linked with 16 KB max/common page
#     sizes. NDK r27 and lower do not enable this automatically, so omitting
#     the flags makes the extension unloadable on 16 KB-page devices.
#
# Required env:
#   ANDROID_NDK_HOME — path to the reproducibly pinned NDK r26d
#                      (26.3.11579264). The script adds the linker flags
#                      required for 16 KB-page-aligned .so files.
#   JAVA_HOME        — JDK 21
#
# Optional env:
#   WORKDIR          — scratch dir; defaults to a per-run mktemp dir.
#                      Pass a persistent path to speed up re-runs (keeps
#                      Media3 + FFmpeg clones warm).
#   MEDIA3_TAG       — override the pinned `1.11.0`
#   FFMPEG_TAG       — override the pinned `n6.0`
#
# Usage:
#   ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264 \
#   JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
#   bash scripts/build-ffmpeg-aar.sh
#
# See scripts/README-ffmpeg-aar.md for prereqs and troubleshooting.

set -euo pipefail

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

MEDIA3_TAG=${MEDIA3_TAG:-1.11.0}
FFMPEG_TAG=${FFMPEG_TAG:-n6.0}
ANDROID_API_LEVEL=21            # FFmpeg NDK floor; Silo's app minSdk is 24
ENABLED_DECODERS=(ac3 eac3 mlp truehd dca alac)

# If you change this list, also audit:
#   * android-shared/.../PlaybackCapabilityDetector.kt codec list in Phase 3
#   * docs/plans/ffmpeg-audio-extension-plan.md codec table
SKIP_ABIS=(x86)                 # keep armeabi-v7a / arm64-v8a / x86_64

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT_AAR="$REPO_ROOT/android-shared/libs/media3-decoder-ffmpeg-${MEDIA3_TAG}.aar"

WORKDIR=${WORKDIR:-$(mktemp -d -t silo-ffmpeg-XXXXXX)}
MEDIA3_DIR="$WORKDIR/media-${MEDIA3_TAG}"
EXT_MODULE="$MEDIA3_DIR/libraries/decoder_ffmpeg"
FFMPEG_MODULE_PATH="$EXT_MODULE/src/main"
FFMPEG_SRC="$FFMPEG_MODULE_PATH/jni/ffmpeg"
BUILD_SCRIPT="$FFMPEG_MODULE_PATH/jni/build_ffmpeg.sh"

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------

log() { printf "\n\033[1;34m[build-ffmpeg-aar]\033[0m %s\n" "$*"; }
die() { printf "\n\033[1;31m[build-ffmpeg-aar] ERROR:\033[0m %s\n" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

log "Preflight"

[[ -n "${ANDROID_NDK_HOME:-}" ]] || die "ANDROID_NDK_HOME is not set. Install NDK r26d and export ANDROID_NDK_HOME."
[[ -d "$ANDROID_NDK_HOME" ]]      || die "ANDROID_NDK_HOME ($ANDROID_NDK_HOME) does not exist."

if [[ -f "$ANDROID_NDK_HOME/source.properties" ]]; then
    ndk_version=$(grep "^Pkg.Revision" "$ANDROID_NDK_HOME/source.properties" | awk -F= '{print $2}' | tr -d ' ')
    log "  NDK:      $ndk_version ($ANDROID_NDK_HOME)"
    if [[ ! "$ndk_version" =~ ^26\. ]]; then
        printf "\033[1;33m  Warning: NDK %s is not r26.x — the plan pins r26d. Press Enter to continue or Ctrl-C to abort.\033[0m\n" "$ndk_version" >&2
        read -r
    fi
fi

[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set. Export JAVA_HOME pointing to JDK 21."
java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
log "  Java:     $java_version"
case "$java_version" in
    *\"21.*|*\"22.*|*\"23.*|*\"24.*|*\"25.*) ;;
    *) die "JAVA_HOME ($JAVA_HOME) must be JDK 21+. Got: $java_version" ;;
esac

for bin in git make curl unzip shasum python3; do
    command -v "$bin" >/dev/null || die "Required tool not on PATH: $bin"
done

# macOS hosts cross-compile with the NDK's darwin-x86_64 prebuilt toolchain
# (Rosetta on Apple Silicon; universal binaries in newer NDKs).
case "$(uname -s)" in
    Darwin) HOST_PLATFORM=darwin-x86_64 ;;
    Linux)  HOST_PLATFORM=linux-x86_64  ;;
    *)      die "Unsupported host: $(uname -s). Use macOS or Linux x86_64/arm64." ;;
esac
log "  Host:     $HOST_PLATFORM"

log "  Workdir:  $WORKDIR"
log "  Output:   $OUTPUT_AAR"
log "  Decoders: ${ENABLED_DECODERS[*]}"
log "  Skipping: ${SKIP_ABIS[*]}"

mkdir -p "$(dirname "$OUTPUT_AAR")"

# ---------------------------------------------------------------------------
# Clone Media3 at the pinned tag
# ---------------------------------------------------------------------------

if [[ ! -d "$MEDIA3_DIR/.git" ]]; then
    log "Cloning androidx/media at tag $MEDIA3_TAG"
    git clone --depth=1 --branch "$MEDIA3_TAG" \
        https://github.com/androidx/media.git "$MEDIA3_DIR"
else
    log "Media3 clone already present at $MEDIA3_DIR"
fi

[[ -f "$BUILD_SCRIPT" ]] || die "Upstream build script missing at $BUILD_SCRIPT — Media3 layout changed?"

# ---------------------------------------------------------------------------
# Clone FFmpeg into the extension's jni/ffmpeg
# ---------------------------------------------------------------------------

if [[ ! -d "$FFMPEG_SRC/.git" ]]; then
    log "Cloning FFmpeg at tag $FFMPEG_TAG"
    git clone --depth=1 --branch "$FFMPEG_TAG" \
        https://git.ffmpeg.org/ffmpeg.git "$FFMPEG_SRC"
else
    log "FFmpeg clone already present at $FFMPEG_SRC"
fi

# ---------------------------------------------------------------------------
# Patch upstream build_ffmpeg.sh to skip excluded ABIs
#
# Upstream 1.11.0 hardcodes four per-ABI blocks (armeabi-v7a / arm64-v8a /
# x86 / x86_64). It takes ENABLED_DECODERS as positional args, so the
# decoder list is handled at invocation time — we only need to patch to
# skip ABIs. Each block starts with `./configure \` and ends with
# `make clean`; we identify the x86 block by its --libdir and splice it out.
# ---------------------------------------------------------------------------

if ((${#SKIP_ABIS[@]})); then
    log "Patching build_ffmpeg.sh to skip ABIs: ${SKIP_ABIS[*]}"
    python3 - "$BUILD_SCRIPT" "${SKIP_ABIS[@]}" <<'PY'
import pathlib, re, sys

script_path = pathlib.Path(sys.argv[1])
skip_abis   = set(sys.argv[2:])
text        = script_path.read_text()

# Each per-ABI block spans `./configure \` through the next `make clean\n`.
# The non-greedy quantifier ensures one block at a time.
block_re = re.compile(
    r"\./configure \\\n"
    r"(?:[^\n]*\n)*?"
    r"make clean\n",
    re.MULTILINE,
)

dropped = []
def replace_block(match):
    block = match.group(0)
    m = re.search(r"--libdir=android-libs/([A-Za-z0-9_-]+)", block)
    if m and m.group(1) in skip_abis:
        dropped.append(m.group(1))
        return ""
    return block

new_text = block_re.sub(replace_block, text)
if not dropped:
    print(f"Warning: none of {sorted(skip_abis)} matched — leaving script as-is.", file=sys.stderr)
else:
    script_path.write_text(new_text)
    print(f"  Patched {script_path.name}: dropped ABIs {dropped}")
PY

    # Also restrict the Gradle/CMake side. Media3's decoder_ffmpeg module
    # defaults to building every ABI AGP supports, but without the
    # corresponding FFmpeg static archives that will fail with a missing
    # libswresample.a error. Inject abiFilters into defaultConfig so CMake
    # only invokes ninja for the ABIs we cross-compiled above.
    KEEP_ABIS=(armeabi-v7a arm64-v8a x86_64)
    if [[ -f "$EXT_MODULE/build.gradle.kts" ]]; then
        EXT_BUILD_FILE="$EXT_MODULE/build.gradle.kts"
        EXT_BUILD_DSL=kotlin
    elif [[ -f "$EXT_MODULE/build.gradle" ]]; then
        EXT_BUILD_FILE="$EXT_MODULE/build.gradle"
        EXT_BUILD_DSL=groovy
    else
        die "decoder_ffmpeg has no supported Gradle build file"
    fi
    log "Patching ${EXT_BUILD_FILE##*/} abiFilters to: ${KEEP_ABIS[*]}"
    python3 - "$EXT_BUILD_FILE" "$EXT_BUILD_DSL" "$ndk_version" "${KEEP_ABIS[@]}" <<'PY'
import pathlib, re, sys

script_path = pathlib.Path(sys.argv[1])
build_dsl   = sys.argv[2]
ndk_version = sys.argv[3]
keep_abis   = sys.argv[4:]
text        = script_path.read_text()

if build_dsl == "kotlin":
    anchor = (
        r'(android\s*\{\n\s*namespace\s*=\s*'
        r'"androidx\.media3\.decoder\.ffmpeg"\n)'
    )
    ndk_line = f'  ndkVersion = "{ndk_version}"\n'
    snippet = (
        "  defaultConfig {\n"
        "    ndk {\n"
        "      abiFilters += setOf("
        + ", ".join(f'"{abi}"' for abi in keep_abis)
        + ")\n"
        "    }\n"
        "  }\n\n"
    )
else:
    anchor = (
        r"(android\s*\{\n\s*namespace\s+"
        r"'androidx\.media3\.decoder\.ffmpeg'\n)"
    )
    ndk_line = f"    ndkVersion '{ndk_version}'\n"
    snippet = (
        "    defaultConfig {\n"
        "        ndk {\n"
        "            abiFilters "
        + ", ".join(f"'{abi}'" for abi in keep_abis)
        + "\n"
        "        }\n"
        "    }\n\n"
    )

if "ndkVersion" not in text:
    text, n = re.subn(anchor, lambda match: match.group(1) + ndk_line, text, count=1)
    if n != 1:
        sys.stderr.write("Could not pin decoder_ffmpeg ndkVersion.\n")
        sys.exit(2)

if "abiFilters" in text:
    script_path.write_text(text)
    print(f"  Pinned NDK {ndk_version}; abiFilters already present.")
    sys.exit(0)

new_text, n = re.subn(anchor, lambda match: match.group(1) + "\n" + snippet, text, count=1)
if n != 1:
    sys.stderr.write("Could not find android { namespace ... } to inject abiFilters into.\n")
    sys.exit(2)
script_path.write_text(new_text)
print(f"  Pinned NDK {ndk_version} and injected abiFilters into {script_path}")
PY
fi

# NDK r27 and lower do not produce 16 KB-aligned shared libraries by
# default. Patch the final JNI link (not the intermediate static archives)
# with Android's documented compatibility flags. The CI artifact job
# independently reads every packaged ELF program header and rejects an
# alignment below 0x4000.
CMAKE_FILE="$FFMPEG_MODULE_PATH/jni/CMakeLists.txt"
python3 - "$CMAKE_FILE" <<'PY'
import pathlib, sys

cmake_path = pathlib.Path(sys.argv[1])
text = cmake_path.read_text()
if "-Wl,-z,max-page-size=16384" not in text:
    text += """

# Silo: Android 15+ 16 KB-page compatibility for NDK r27 and lower.
target_link_options(
        ffmpegJNI
        PRIVATE "-Wl,-z,max-page-size=16384"
        PRIVATE "-Wl,-z,common-page-size=16384")
"""
    cmake_path.write_text(text)
    print(f"  Added 16 KB ELF alignment flags to {cmake_path}")
else:
    print(f"  16 KB ELF alignment flags already present in {cmake_path}")
PY

# ---------------------------------------------------------------------------
# Cross-compile FFmpeg per ABI (upstream script loops internally)
# ---------------------------------------------------------------------------

log "Running build_ffmpeg.sh (this is the long pole — ~10–15 min on M-series)"
(
    cd "$FFMPEG_MODULE_PATH/jni"
    bash build_ffmpeg.sh \
        "$FFMPEG_MODULE_PATH" \
        "$ANDROID_NDK_HOME" \
        "$HOST_PLATFORM" \
        "$ANDROID_API_LEVEL" \
        "${ENABLED_DECODERS[@]}"
)

# Confirm expected per-ABI static libs exist so we fail fast if the build
# silently skipped something.
for abi in armeabi-v7a arm64-v8a x86_64; do
    for lib in avutil swresample avcodec; do
        f="$FFMPEG_SRC/android-libs/$abi/lib${lib}.a"
        [[ -f "$f" ]] || die "Missing expected static lib: $f"
    done
done
log "All expected static libs present for armeabi-v7a / arm64-v8a / x86_64"

# ---------------------------------------------------------------------------
# Assemble the AAR via Media3's Gradle
# ---------------------------------------------------------------------------

log "Assembling :lib-decoder-ffmpeg:assembleRelease (Kotlin/JNI compile + AAR packaging)"

# Point Gradle at the Android SDK's CMake so externalNativeBuild finds it.
export ANDROID_HOME=${ANDROID_HOME:-}
if [[ -z "$ANDROID_HOME" ]]; then
    # Derive the SDK root from the required side-by-side NDK path first.
    export ANDROID_HOME=$(cd "$ANDROID_NDK_HOME/../.." && pwd)
fi
if [[ -z "$ANDROID_HOME" ]]; then
    # Last-ditch default for macOS Homebrew; scripts/README-ffmpeg-aar.md
    # covers the Linux default.
    if [[ -d /opt/homebrew/share/android-commandlinetools ]]; then
        export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    fi
fi
[[ -n "$ANDROID_HOME" ]] || die "ANDROID_HOME is not set and no default was discoverable."

(
    cd "$MEDIA3_DIR"
    ./gradlew --no-daemon :lib-decoder-ffmpeg:assembleRelease
)

# Media3's Gradle setup redirects `buildDir` to `buildout/` (see its
# common_config.gradle), so check there first, then fall back to the
# standard AGP `build/outputs/aar/` path for any future release layouts.
PRODUCED_AAR=$(find \
    "$EXT_MODULE/buildout/outputs/aar" \
    "$EXT_MODULE/build/outputs/aar" \
    -name "*-release.aar" -print 2>/dev/null | head -n1 || true)
[[ -n "$PRODUCED_AAR" && -f "$PRODUCED_AAR" ]] \
    || die "Could not locate produced AAR under $EXT_MODULE/(buildout|build)/outputs/aar/"

log "Produced AAR: $PRODUCED_AAR"

# ---------------------------------------------------------------------------
# Copy to the repo + report SHA-256
# ---------------------------------------------------------------------------

cp -f "$PRODUCED_AAR" "$OUTPUT_AAR"
SIZE=$(stat -f '%z' "$OUTPUT_AAR" 2>/dev/null || stat -c '%s' "$OUTPUT_AAR")
SHA=$(shasum -a 256 "$OUTPUT_AAR" | awk '{print $1}')

log "Build complete."
printf "\n"
printf "  AAR:      %s\n" "$OUTPUT_AAR"
printf "  Size:     %s bytes (%.2f MiB)\n" "$SIZE" "$(awk "BEGIN {print $SIZE/1048576}")"
printf "  SHA-256:  %s\n" "$SHA"
printf "  Decoders: %s\n" "${ENABLED_DECODERS[*]}"
printf "\n"
printf "Confirm native libs + JNI classes:\n"
printf "  unzip -l %s\n" "$OUTPUT_AAR"
printf "\n"
printf "Note: SHA-256 is informational — FFmpeg builds are not bit-identical across runs.\n"
printf "      See scripts/README-ffmpeg-aar.md \"Reproducibility\".\n"
