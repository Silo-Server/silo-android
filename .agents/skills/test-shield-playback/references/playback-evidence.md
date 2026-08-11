# Shield playback evidence

Use three independent evidence planes. A plan or capability claim is not proof that the sink received that format.

## Server decision

`shield-test plan` reads the configured dev database and identifies the decision
for this Shield by manufacturer and model, also accepting the configured device
codename when a client supplies that optional field. Check:

- `delivery` for original HTTP, progressive, HLS, or terminal behavior; the
  platform-neutral v3 contract no longer exposes a client-engine name.
- `decision_reason` and transformations for why the route was selected.
- `effective_recipe` for the video codec, dynamic range, resolution, frame rate, audio codec, layout, and channel count.
- The request's advertised Dolby Vision profiles and audio passthrough codecs.

The plan proves what the server instructed. It does not prove decoder initialization, an HDMI mode switch, or passthrough at AudioFlinger.

For an HDR negotiation report, use `find-hdr <range>` to select an exact source
file and `capabilities` to compare decoder/delivery claims with output claims. A
rejected start may not create a `playback_v3_attempts` row, so an empty
exact-content `plan` result plus a newer on-screen terminal is meaningful; do
not substitute an older successful plan.

## Android player

`shield-test logs` focuses on `SiloDeepLink`, `TvPlayerScreen`, `TvPlayerViewModel`, `AudioCapabilityMgr`, `Media3Analytics`, `HdrDisplayController`, `RefreshRateMatcher`, and `SiloDovi`.

Useful signals include:

- `deep link arrived` for successful link routing.
- `Video format` and `Audio format` for the selected Media3 inputs.
- `Video decoder` and `Audio decoder` for the initialized components.
- `Track snapshot` for the selected versus merely available tracks.
- `Audio output capabilities updated` after an HDMI or receiver route change.
- `PlaybackSessionMgr` capability and start-result lines on builds that include that diagnostic tag.
- `Applied display mode` for an app-requested refresh-rate switch.
- `Preflight fallback`, `Startup stall fallback`, `Player error`, load errors, underruns, and dropped frames for failure analysis.

Clear logs only immediately before a controlled reproduction. Otherwise preserve history.

## Platform output

### Dolby Vision and HDR

Require all available evidence:

1. The server recipe remains `dynamic_range=dolby_vision` rather than `hdr10` or `sdr`.
2. Media3 selects a Dolby Vision input and initializes the expected hardware decoder or documented client-side transformation.
3. No later fallback or route event replaces that path.
4. The connected TV or receiver reports Dolby Vision when definitive sink-level proof is required.

`dumpsys display` exposes supported HDR types and the active timing mode, but on older Shield software it does not reliably identify the live HDMI electro-optical transfer function. Do not call Dolby Vision confirmed from HDR capability enumeration alone.

### HLG false negatives

Keep decoder and output evidence separate:

1. `find-hdr hlg` must identify a track with `video_range_type=HLG` or `color_transfer=arib-std-b67`.
2. `capabilities` should show whether the Shield has an HEVC/VP9/AV1 HDR-capable decoder, independently of the submitted `hdr_details.hlg` output claim.
3. `display` shows what Android enumerates for the current sink. On older Shield builds, omission of `HDR_TYPE_HLG` can be an output-probe false negative even when Main10 decode and the physical chain can render HLG.
4. An exact-content plan or the on-screen/server terminal establishes the consequence. If 4K transcoding is disabled, `no_alternate_version` is evaluated before `hdr_transcode_unsupported`; this ordering does not disprove the HLG direct-play failure.

Do not fix this by treating every device as HLG-capable. Preserve codec capability separately from display reporting and use a narrowly identified device/output quirk where Android is known to under-report HLG.

### Frame-rate matching

Compare:

1. The recipe or Media3 input frame rate, such as `23.976`.
2. `HdrDisplayController`'s requested mode.
3. `shield-test display` after the HDMI handshake. The active `DisplayModeRecord` should show the matching rate, such as approximately `23.976`, rather than the launcher rate of approximately `59.94`.

Check after playback has stabilized. A display dump taken after the player exits normally shows the restored launcher mode.

### Audio passthrough

Use a controlled reproduction because Android may attribute a direct patch track to `audioserver`, not the app PID. Require:

1. The server recipe retains the source codec and layout, for example TrueHD 7.1.
2. `AudioCapabilityMgr` advertises that codec for the current HDMI route.
3. Media3 selects the matching source audio track.
4. `shield-test audio` shows a non-standby `DIRECT` output with the compressed `Processing format`, the HDMI/AUX digital output device, and direct or IEC61937/IEC958 flags. For TrueHD, prefer the text `AUDIO_FORMAT_DOLBY_TRUEHD`; the Shield commonly also prints numeric format `0x0e000000`.
5. The receiver or soundbar reports the expected format when definitive sink-level proof is required.

A selected `audio/true-hd` Media3 format alone can still be decoded to PCM. Capability claims alone only describe what the route said it supports.

### Audio sync

Record the sign convention and a visibly large test value before tuning small offsets. Confirm whether the symptom changes between compressed passthrough and PCM decode; HDMI/TV/soundbar processing can add latency outside the app. Preserve the exact title, file ID, track index, output route, display mode, and player path with the result.

## Route changes and fallbacks

HDMI hotplug, moving the Shield, changing the receiver, or toggling TV audio settings invalidates earlier capability evidence. Restart the title and collect a new plan, logs, active display mode, and AudioFlinger state.

When the app falls back, establish the first causal signal in time order. Later decoder errors or a compatibility-player banner may be consequences rather than the trigger. Correlate device timestamps with `shield-test events` and the server plan creation time.
