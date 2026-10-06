# Third-Party Notices

Silo Android is licensed under `AGPL-3.0-or-later`. Third-party dependencies keep their original licenses.

## Media3 FFmpeg Decoder AAR

This repository includes `android-shared/libs/media3-decoder-ffmpeg-1.11.0.aar`, built from AndroidX Media3 1.11.0 and FFmpeg n6.0 using `scripts/build-ffmpeg-aar.sh`.

The local build script is intended to build FFmpeg in LGPL-only mode. Do not enable GPL or nonfree FFmpeg options without updating the release process and downstream distribution obligations.

Rebuild and source instructions are in [scripts/README-ffmpeg-aar.md](scripts/README-ffmpeg-aar.md).

## libass Subtitles

Silo uses [`ass-media` 0.5.1](https://github.com/peerless2012/libass-android) under the MIT license to integrate authored ASS/SSA subtitle rendering with AndroidX Media3. Its native package includes [`libass`](https://github.com/libass/libass), distributed under the ISC license, plus libass's font and text-shaping dependencies under their respective upstream licenses.

## libdovi

The Dolby Vision bridge AAR is built from the `dolby_vision` crate in [`quietvoid/dovi_tool` 2.3.1](https://github.com/quietvoid/dovi_tool/tree/b25558062e4a56973482ec70133bd7b891320e48/dolby_vision), distributed under the MIT license. The upstream source commit, archive and lockfiles, the OSV-clean build lock, toolchains, native outputs, and packaged AAR are pinned in `android-shared/src/native/dovi/provenance.json`. The build lock records remediations for RUSTSEC-2026-0190, RUSTSEC-2026-0105, and RUSTSEC-2026-0204.

## TMDB Logo

`android-shared/src/androidMain/res/drawable/tmdb_logo.xml` is TMDB's approved "alt short" logo from <https://www.themoviedb.org/about/logos-attribution>, converted to a VectorDrawable without changing its shape or colours. It is a trademark of TMDB and is not covered by this repository's license. Silo shows it next to TMDB scores; keep it unmodified and less prominent than Silo's own branding.

## Gradle Wrapper

The Gradle wrapper scripts retain their upstream Apache-2.0 license.
