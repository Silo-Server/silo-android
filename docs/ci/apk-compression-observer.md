# Private APK archive observer

This optional C0 observation measures the release artifact archive at levels 0,
1 and 6 using the eight signed synthetic APKs produced in the same job. Set the
manual workflow input `observe_compression` to enable it. The default is false;
all primary M/C0 comparisons keep it false. The observer starts after assembly,
strict artifact verification and the existing report upload. Its CPU and elapsed
time are outside `report.elapsed_seconds`. Enabled jobs include observation time
in their GitHub API spans, so their summary and observer receipt explicitly mark
`primary_whole_job_timing_eligible: false`.

The application source is `78f6b3e2ec8898755da381365f8f7d21e2e63dd9`. The consumer
controller is the actual clean `GITHUB_WORKFLOW_SHA`, including all thirteen
sealed controller files checked against Git objects. This candidate composes the
accepted safe toolchain observations with the accepted a73 native inventory
contract. Root must accept the final composed controller and actual new seed
provenance before measurements. A successful seed cannot guarantee eligible
arms: the existing `ubuntu-latest` label permits image changes, and every actual
arm must pass its toolchain guards. The four compatibility keys remain `java`,
`gradle`, `agp` and `runner_image`; full SDK, CPU and memory dictionaries and all
difference keys are retained. No generic seed compatibility exception is added.
The existing exact historical 540 seed exception remains in the restore helper.

## Same-job acquisition

The existing artifacts retain JSON, Gradle logs and profiles, plus the SEED
Gradle home. They do not retain signed APKs or generated sources. The optional
observer therefore rechecks the live C0 source outputs before the hosted job
ends. It consumes the existing same-job qualified report and approved seed
restore receipt. It invokes the original strict inventory with SDK build-tools
36.0.0 and requires complete equality, including signed APK hashes, signatures,
certificate, package/feature/version metadata, native member dictionaries,
DEX/resources, BuildConfig, R8 mapping and actual packaged profile presence and
bytes. Profile absence is allowed when the producer actually packaged none;
no mandatory `.profm` is invented. Generated synthetic FCM resources are checked
again. Production release environment values, dirty tracked source, incomplete
inventories, symlink inputs and payload changes reject the observer.

No build, profile generation or dependency download is added. Only the existing
eight outputs feed every level. All temporary ZIPs and staging copies are deleted
after verification. The bounded JSON receipt is written locally and emitted with
`APK_COMPRESSION_RECEIPT=` in the normal job log. No raw APK or observer archive
is uploaded, and no additional artifact upload is added.

## Actual Node24 runtime

The pinned upload action metadata historically declares `runs.using: node20`.
GitHub [removed Node20 from Actions on September 23, 2026](https://github.blog/changelog/2026-09-23-node-20-is-no-longer-available-in-github-actions/),
forces JavaScript actions onto Node24 and removed the opt-out. The historical
metadata does not prove the selected hosted runtime.

The optional private local action declares `runs.using: node24` and directly
records `process.execPath`, executable SHA-256, exact Node/zlib versions, platform,
architecture, run/attempt, controller and actual image. It emits the resolved
binary path as an action output. The archive observer uses that same binary,
checks its bytes before and after every phase, and rejects a differing child
Node/zlib/platform/architecture receipt. This directly observes the selected
local JavaScript action runtime. It does not instrument the upload action's
already completed invocation; its relation to that action is GitHub's forced
Node24 policy plus the exact pinned archive implementation below. Actual hosted
runtime and zlib qualification remain necessary. Local fixture results on Node
24.20.0, macOS arm64 prove archive mechanics only.

## Release consumer and pinned implementation

The sealed `.github/workflows/release.yml` builds four APKs per module and copies
the universal APK twice under latest release and legacy debug aliases. Its two
upload directories therefore contain six entries each, referencing eight unique
signed containers. The observer reproduces those twelve entries with version
0.0.1. The release uploader uses its default level 6, and the release publisher
extracts the archives before passing APK paths to `gh release upload`.

The already downloaded
[upload action bundle](https://raw.githubusercontent.com/actions/upload-artifact/330a01c490aca151604b8cf639adc76d48f6c5d4/dist/upload/index.js)
must have SHA-256
`168c44946cba03564808c19b83a72601e6d1d6f57081dbd2d6e2ea1cedc522f5`.
The cached runner action path is supplied explicitly; an absent or changed bundle
rejects the observer. No fallback fetch or package install occurs. Only the
startup expression and logging facade are replaced, then the original bundled
`findFilesToUpload`, `getUploadZipSpecification` and `createZipUploadStream`
modules run. Archive, glob, CRC, stream and compression dependencies retain their
pinned bytes; external network and subprocess modules are blocked in that VM.
The pinned lockfile records `@actions/artifact` 4.0.0, `archiver` 7.0.1,
`zip-stream` 6.0.1, `compress-commons` 6.0.2 and `crc32-stream` 6.0.0.

The timer and process CPU sample begin immediately before archive stream
creation and end when the local drain closes. Bundle loading, glob discovery,
signature checks, input hashing and ZIP verification are outside the timer.
Local drain includes filesystem writes. Each module runs sequentially at levels
0, 1, 6, producing six archive byte/SHA/CPU/wall receipts and per-level totals.
Every extracted entry, including aliases, must match the original signed hash
and size. Original and staging input bytes, file identity and modification
metadata are checked around every phase.

These totals measure archive creation on one runner and one ordered cohort.
They contain no network transfer, server finalization, release publication or
assemble speed estimate. The local unsigned fixture receipt sets
`fixture_tests_only: true`, `actual_signed_apk_qualification: false` and
`qualified_input_verified: false`. No actual signed compression result exists
until a separately accepted hosted C0 observation completes all guards. The
observer remains disabled during the primary layout comparisons.
