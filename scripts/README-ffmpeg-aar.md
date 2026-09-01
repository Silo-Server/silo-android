# Rebuilding `media3-decoder-ffmpeg-1.11.0.aar`

This directory ships `scripts/build-ffmpeg-aar.sh`, which produces the Media3
FFmpeg audio-decoder AAR checked in at
`android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar`.

Google never publishes this extension as a Maven artifact — FFmpeg's LGPL has
distribution requirements Google opted out of. The AAR is therefore built
from source and checked into the repo so reviewers and CI don't have to run
a 30-minute cross-compile on every clone.

You only need to re-run this script when:

- We bump `media3` in [gradle/libs.versions.toml](../gradle/libs.versions.toml).
- We change the decoder list (see "Tweaking the build" below).
- A new NDK drops 16 KB page-size support for a device we care about.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Android NDK | **r26d** (`26.3.11579264`) | `sdkmanager --install "ndk;26.3.11579264"` |
| Android SDK CMake | **3.22.1+** | `sdkmanager --install "cmake;3.22.1"` |
| FFmpeg source | **n6.0** | Cloned automatically by the script |
| androidx/media source | **1.11.0** | Cloned automatically by the script |
| Java | **JDK 21+** | Homebrew: `brew install openjdk@21` |
| `git`, `make`, `python3`, `unzip`, `shasum` | any recent | System tools |
| Disk headroom | **~25 GB** | For the working directory |
| Build time | **20–45 min** | On an M5 Pro: ~25 min first try, ~20 min re-run |

NDK r26d is the reproducibility pin tested by Media3's native build. Because
r27 and lower do not emit 16 KB-aligned shared libraries by default, the script
patches the final CMake link with Android's documented max/common-page-size
flags. The GitHub artifact job verifies every packaged ELF program header;
without that alignment, the AAR can fail at JNI load on 16 KB-page devices.
Newer NDKs are not substituted implicitly.

---

## Usage

From the repo root:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264

bash scripts/build-ffmpeg-aar.sh
```

On completion the script prints:

```
  AAR:      /path/to/android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar
  Size:     12345678 bytes (11.77 MiB)
  SHA-256:  <hex digest>
  Decoders: ac3 eac3 mlp truehd dca alac
```

To confirm the AAR contains the native libs and JNI classes:

```sh
unzip -l android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar \
    | grep -E '(\.so|FfmpegAudioRenderer)'
```

You should see one `libffmpegJNI.so` per ABI (`arm64-v8a`, `armeabi-v7a`,
`x86_64`) and the `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` class.

---

## Reproducibility

**The SHA-256 is informational, not an integrity gate.** FFmpeg produces
non-deterministic static archive timestamps, which propagate through the
CMake + AGP packaging steps. Two runs of this script on the same host
against the same source tree will usually differ in SHA-256.

What is stable:

- The enabled decoder list and the set of `libav*.a` symbols inside each
  ABI's `.so` — these are the bits that matter for behavior.
- `aapt2 dump badging` output (Android manifest, `native-code` ABIs).
- The output of the plan's `FfmpegClasspathTest` and codec-capability
  checks.

If a reviewer's local rebuild produces a different SHA, don't treat it as
suspicious — use `diff <(unzip -p original.aar classes.jar | jar tvf -) <(...)`
or `aapt2 dump badging` to diff the reviewable surface instead.

---

## Tweaking the build

Edit `scripts/build-ffmpeg-aar.sh`:

- **`ENABLED_DECODERS`** — list of FFmpeg decoder names. Audit three files
  together whenever you change this:
  1. `scripts/build-ffmpeg-aar.sh` (this script)
  2. `android-shared/.../FfmpegAudioSupport.kt` — the advertised codec list
     that the capability detector returns
  3. `docs/plans/ffmpeg-audio-extension-plan.md` — the codec table
- **`SKIP_ABIS`** — ABIs to remove from the upstream build. Default is
  `x86` (no target device uses 32-bit x86; our emulator is x86_64).
- **`MEDIA3_TAG`** / **`FFMPEG_TAG`** — source versions. Media3 1.11.0's
  upstream README pins FFmpeg 6.0 with a note that n7.x breaks the JNI
  glue. Don't bump FFmpeg without checking upstream first.

The script passes `ENABLED_DECODERS` as positional args to upstream
`build_ffmpeg.sh` — no patching of the upstream script is needed for
decoder changes. ABI exclusions **are** patched into the upstream script
at build time (its four-ABI blocks are hardcoded).

The pinned set maps AC-3/E-AC-3 to `ac3`/`eac3`, TrueHD to `mlp` and
`truehd`, DTS core/Express/HRA/MA to `dca`, and ALAC to `alac`. ALAC is kept
because Media3 1.11 can extract ALAC from Matroska while Android does not
guarantee a platform decoder for it.

---

## Troubleshooting

**`sdkmanager` refuses to run with "requires JDK 17 or later"**
Your shell picked up a system JDK 11 on PATH. Re-export `JAVA_HOME` and
prepend `$JAVA_HOME/bin` to `PATH` before running the script, e.g.:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
```

**`CMake not found at expected version 3.21.0+`**
Install via the SDK manager, not Homebrew — AGP looks in
`$ANDROID_HOME/cmake/` specifically:

```sh
sdkmanager --install "cmake;3.22.1"
```

**`ARMv7 Clang compiler … does not exist` or NDK toolchain errors**
Your `ANDROID_NDK_HOME` points at the wrong version. Confirm with
`cat $ANDROID_NDK_HOME/source.properties` — it should show
`Pkg.Revision = 26.3.11579264`.

**`./gradlew` inside the Media3 workdir fails with "No signature of method"**
Media3 1.11.0's Gradle requires JDK 21. If Gradle detects JDK 11 or 17
it'll fall over with cryptic Groovy errors. Double-check `$JAVA_HOME`.

**Build succeeds but the AAR is missing an ABI's `.so`**
Check the script's `SKIP_ABIS` array — if you expected `armeabi-v7a` but
didn't see it, it was skipped. The script prints `Skipping: <abis>`
during preflight.

**`git clone` of FFmpeg mirrors fails with TLS errors**
The upstream mirror is `git://source.ffmpeg.org/ffmpeg` (insecure git
protocol). If your network blocks git:// the script falls back to
`https://git.ffmpeg.org/ffmpeg.git`. If both fail, mirror via GitHub:
`git clone --branch n6.0 https://github.com/FFmpeg/FFmpeg.git`.

**Re-running the script is slow**
Pass a persistent `WORKDIR` to avoid re-cloning Media3 (~150 MB) and
FFmpeg (~200 MB) on every run:

```sh
WORKDIR=$HOME/tmp/silo-ffmpeg bash scripts/build-ffmpeg-aar.sh
```

The Media3 and FFmpeg clones are reused across runs; only the FFmpeg
cross-compile reruns (~10–15 min).
