package org.siloserver.silo.common.player.video

import org.siloserver.silo.common.player.Playability
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import org.siloserver.silo.model.playback.PlayMethod

/**
 * Detects a playback session wedged in BUFFERING with no forward progress —
 * both at STARTUP ("Media3 accepted this but playback never started") and
 * MID-STREAM ("it was playing, then froze forever"). Preflight covers known
 * unsupported tracks and player errors; this covers the silent buffer-forever
 * case that never surfaces an error. Applies to ALL routes (direct, remux,
 * transcode, HLS) — a wedged compatibility-route remux used to have no watchdog
 * at all (TV QA 2026-07-10: some remuxes buffer indefinitely).
 */
class PlaybackStartupStallDetector(
    private val startupGraceMs: Long = DEFAULT_STARTUP_GRACE_MS,
    private val midStreamGraceMs: Long = DEFAULT_MID_STREAM_GRACE_MS,
    private val clientTransformGraceMs: Long = DEFAULT_CLIENT_TRANSFORM_GRACE_MS,
    private val startedProgressMs: Long = DEFAULT_STARTED_PROGRESS_MS,
    private val bufferedProgressMs: Long = DEFAULT_BUFFERED_PROGRESS_MS,
) {
    private var sessionKey: String? = null
    private var startPositionMs: Long = 0L
    private var started = false
    private var signaled = false
    private var firstFrameRendered = false
    private var decoderStartupAtMs: Long? = null
    private var clientDolbyVisionTransform = false
    private var clientTransformEvidenceAtMs: Long? = null
    private var clientTransformPositionMs: Long = 0L
    private var clientTransformDecoderOutputCount: Int = 0
    private var clientTransformProgressAtMs: Long = 0L
    private var paused = false
    // Last time playback made forward progress (or the mount time before it
    // starts). The stall is measured from here, so the same logic covers a
    // never-started session and a mid-stream freeze.
    private var lastProgressPositionMs: Long = 0L
    private var lastBufferedPositionMs: Long = 0L
    private var lastProgressAtMs: Long = 0L

    fun onMounted(
        sessionKey: String,
        playMethod: PlayMethod,
        startPositionMs: Long,
        nowMs: Long,
        clientTransformations: Collection<String> = emptyList(),
    ) {
        if (this.sessionKey == sessionKey) return
        this.sessionKey = sessionKey
        this.startPositionMs = startPositionMs.coerceAtLeast(0L)
        this.started = false
        this.signaled = false
        this.firstFrameRendered = false
        // Only the startup deadline is cleared here — this is NOT a counter
        // baseline, despite the shape of the check in sample(). Taking one was
        // tried and reverted: Media3 creates fresh DecoderCounters when a
        // renderer is enabled, so a baseline captured at mount can be compared
        // against a counter that restarted at zero, and a healthy stream then
        // looks frozen until it has rendered as many frames again. That trades
        // a rare missed freeze for a common invented one. The residual — a
        // reused player whose cumulative count makes the first sample look like
        // this attempt already rendered — is accepted, and the real fix is
        // AnalyticsListener.onRenderedFirstFrame(EventTime) carried through a
        // mount key, which needs hardware to validate.
        this.decoderStartupAtMs = null
        this.clientDolbyVisionTransform = clientTransformations.any {
            it == CLIENT_DV7_TO_DV81 || it == CLIENT_DV7_TO_HDR10
        }
        this.clientTransformEvidenceAtMs = null
        this.clientTransformPositionMs = this.startPositionMs
        this.clientTransformDecoderOutputCount = 0
        this.clientTransformProgressAtMs = nowMs
        this.paused = false
        this.lastProgressPositionMs = this.startPositionMs
        this.lastBufferedPositionMs = this.startPositionMs
        this.lastProgressAtMs = nowMs
    }

    /**
     * A frame rendered. Which stream rendered it is NOT known.
     *
     * Media3's callback carries no identity, so one from an outgoing stream can
     * vouch for its replacement. That is a real defect and it is deliberately
     * left in place: the two cheaper alternatives are both worse.
     *
     * Qualifying the callback with a key rebuilt from live state fails, because
     * that key describes when the event was DELIVERED, not what rendered it.
     * Comparing decoder counters against a mount baseline fails too, because
     * Media3 creates fresh DecoderCounters when a renderer is enabled — so an
     * outgoing count compared against a restarted counter would make a healthy
     * stream look frozen until it had rendered as many frames again, which
     * trades a rare missed freeze for a common false one.
     *
     * The correct fix is AnalyticsListener.onRenderedFirstFrame(EventTime),
     * whose EventTime identifies the media period, carried through a mount key
     * on the MediaItem tag. That is Media3 integration work whose failure modes
     * are device-specific, and it is not being written blind.
     */
    fun onFirstFrameRendered() {
        firstFrameRendered = true
        decoderStartupAtMs = null
    }

    fun sample(
        sessionKey: String,
        nowMs: Long,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean,
        currentPositionMs: Long,
        bufferedPositionMs: Long,
        decoderInputBufferCount: Int = 0,
        decoderRenderedOutputBufferCount: Int = 0,
        decoderSkippedOutputBufferCount: Int = 0,
        decoderDroppedBufferCount: Int = 0,
    ): Playability.StartupStalled? {
        if (sessionKey != this.sessionKey) return null

        val decoderOutputCount = decoderRenderedOutputBufferCount +
            decoderSkippedOutputBufferCount + decoderDroppedBufferCount
        if (!firstFrameRendered && decoderRenderedOutputBufferCount > 0) {
            firstFrameRendered = true
            decoderStartupAtMs = null
        }

        if (!playWhenReady) {
            paused = true
            decoderStartupAtMs = null
            lastProgressAtMs = nowMs
            clientTransformProgressAtMs = nowMs
            return null
        }
        if (paused) {
            paused = false
            // Resume begins a fresh startup/stall grace period; time spent
            // paused is not evidence of a decoder or transport failure.
            decoderStartupAtMs = null
            lastProgressAtMs = nowMs
            clientTransformProgressAtMs = nowMs
        }

        val hasClientTransformDecodeEvidence = clientDolbyVisionTransform &&
            (firstFrameRendered || decoderInputBufferCount > 0 || decoderOutputCount > 0)
        if (hasClientTransformDecodeEvidence && clientTransformEvidenceAtMs == null) {
            clientTransformEvidenceAtMs = nowMs
            clientTransformPositionMs = currentPositionMs
            clientTransformDecoderOutputCount = decoderOutputCount
            clientTransformProgressAtMs = nowMs
        }
        val clientTransformSeekedBackward = currentPositionMs < clientTransformPositionMs
        clientTransformPositionMs = currentPositionMs
        if (clientTransformSeekedBackward) {
            // A seek or timeline replacement starts a fresh local-transform
            // deadline just as it does for the transport progress clock.
            clientTransformDecoderOutputCount = decoderOutputCount
            clientTransformProgressAtMs = nowMs
        } else if (
            hasClientTransformDecodeEvidence &&
            decoderOutputCount != clientTransformDecoderOutputCount
        ) {
            // Playback position can advance on audio alone while the video
            // transform is wedged. Only decoded video output proves this local
            // recipe is still making progress. A counter reset also starts a
            // fresh deadline because Media3 may replace DecoderCounters with a
            // new renderer instance during a timeline replacement.
            clientTransformDecoderOutputCount = decoderOutputCount
            clientTransformProgressAtMs = nowMs
        }

        // A Profile 7 client transform can consume bytes and emit an initial
        // frame before wedging locally. Decoder output then makes the generic
        // classifier call this a transport stall, causing the client to reopen
        // the same doomed route before it ever asks the server for another
        // recipe. Once the transform has reached the decoder, use a separate
        // bounded progress deadline and identify the failed local recipe. This
        // deliberately does not cover a route with zero decoder evidence: a
        // genuine no-input network stall keeps the normal transport retry.
        if (!signaled && hasClientTransformDecodeEvidence &&
            (isBuffering || isPlaying) &&
            nowMs - clientTransformProgressAtMs > clientTransformGraceMs
        ) {
            signaled = true
            return Playability.StartupStalled(
                bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L),
                stalledForMs = nowMs - clientTransformProgressAtMs,
                classification = DV7_TRANSFORM_STALL_CLASSIFICATION,
            )
        }

        // Audio may advance the position and set isPlaying=true while video is
        // wedged before its first output. This deadline is deliberately
        // independent from the transport/buffering progress clock.
        if (!signaled && !firstFrameRendered && playWhenReady && decoderInputBufferCount > 0) {
            val decoderStart = decoderStartupAtMs ?: nowMs.also { decoderStartupAtMs = it }
            val decoderStalledForMs = nowMs - decoderStart
            if (decoderStalledForMs > startupGraceMs) {
                signaled = true
                return Playability.StartupStalled(
                    bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L),
                    stalledForMs = decoderStalledForMs,
                    classification = if (decoderOutputCount == 0) {
                        "decoder_no_output"
                    } else {
                        "render_startup_failure"
                    },
                )
            }
        }

        // Forward progress (actively playing, or position advanced meaningfully)
        // re-anchors the stall clock and latches "started".
        if (isPlaying || currentPositionMs - lastProgressPositionMs >= startedProgressMs) {
            lastProgressPositionMs = currentPositionMs
            lastProgressAtMs = nowMs
            started = true
        }

        // Downloading media is transport progress before the first frame.
        // After rendering starts, buffer growth alone must not hide a frozen
        // decoder; only playback/output progress may re-anchor the clock.
        if (!firstFrameRendered && decoderInputBufferCount == 0 &&
            bufferedPositionMs - lastBufferedPositionMs >= bufferedProgressMs
        ) {
            lastBufferedPositionMs = bufferedPositionMs
            lastProgressAtMs = nowMs
        } else if (bufferedPositionMs < lastBufferedPositionMs) {
            // A seek or timeline replacement can move the buffer backwards.
            lastBufferedPositionMs = bufferedPositionMs
        }

        if (signaled) return null
        if (!isBuffering) return null

        // Longer grace before playback ever starts (initial handshake/buffer)
        // than for a mid-stream freeze once it has been playing.
        val stalledForMs = nowMs - lastProgressAtMs
        if (stalledForMs <= (if (started) midStreamGraceMs else startupGraceMs)) return null

        signaled = true
        return Playability.StartupStalled(
            bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L),
            stalledForMs = stalledForMs,
            classification = if (
                decoderInputBufferCount > 0 &&
                decoderOutputCount == 0
            ) {
                "decoder_no_output"
            } else {
                "transport_stall"
            },
        )
    }

    companion object {
        const val DEFAULT_STARTUP_GRACE_MS: Long = 20_000L
        const val DEFAULT_MID_STREAM_GRACE_MS: Long = 20_000L
        const val DEFAULT_CLIENT_TRANSFORM_GRACE_MS: Long = 10_000L
        const val DEFAULT_STARTED_PROGRESS_MS: Long = 1_500L
        const val DEFAULT_BUFFERED_PROGRESS_MS: Long = 250L
        const val DV7_TRANSFORM_STALL_CLASSIFICATION = "dv7_transform_stall"
    }
}
