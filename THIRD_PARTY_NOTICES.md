# Third-Party Notices

Silo Android is licensed under `AGPL-3.0-or-later`. Third-party dependencies keep their original licenses.

## Media3 FFmpeg Decoder AAR

This repository includes `android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar`, built from AndroidX Media3 1.11.0 and FFmpeg n6.0 using `scripts/build-ffmpeg-aar.sh`.

The local build script is intended to build FFmpeg in LGPL-only mode. Do not enable GPL or nonfree FFmpeg options without updating the release process and downstream distribution obligations.

Rebuild and source instructions are in [scripts/README-ffmpeg-aar.md](scripts/README-ffmpeg-aar.md).

## libass Subtitles

Silo uses [`ass-media` 0.4.0](https://github.com/peerless2012/libass-android) under the MIT license to integrate authored ASS/SSA subtitle rendering with AndroidX Media3. Its native package includes [`libass`](https://github.com/libass/libass), distributed under the ISC license, plus libass's font and text-shaping dependencies under their respective upstream licenses.

## libdovi

The Dolby Vision bridge AAR is built from the `dolby_vision` crate in [`quietvoid/dovi_tool` 2.3.1](https://github.com/quietvoid/dovi_tool/tree/b25558062e4a56973482ec70133bd7b891320e48/dolby_vision), distributed under the MIT license. The upstream source commit, archive and lockfiles, the OSV-clean build lock, toolchains, native outputs, and packaged AAR are pinned in `android-shared/src/native/dovi/provenance.json`. The build lock records remediations for RUSTSEC-2026-0190, RUSTSEC-2026-0105, and RUSTSEC-2026-0204.

## Gradle Wrapper

The Gradle wrapper scripts retain their upstream Apache-2.0 license.
