package org.siloserver.silo.common.player.video

import org.siloserver.silo.common.player.Playability
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
    private val startedProgressMs: Long = DEFAULT_STARTED_PROGRESS_MS,
    private val bufferedProgressMs: Long = DEFAULT_BUFFERED_PROGRESS_MS,
) {
    private var sessionKey: String? = null
    private var startPositionMs: Long = 0L
    private var started = false
    private var signaled = false
    private var firstFrameRendered = false
    private var decoderStartupAtMs: Long? = null
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
    ) {
        if (this.sessionKey == sessionKey) return
        this.sessionKey = sessionKey
        this.startPositionMs = startPositionMs.coerceAtLeast(0L)
        this.started = false
        this.signaled = false
        this.firstFrameRendered = false
        this.decoderStartupAtMs = null
        this.paused = false
        this.lastProgressPositionMs = this.startPositionMs
        this.lastBufferedPositionMs = this.startPositionMs
        this.lastProgressAtMs = nowMs
    }

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
            return null
        }
        if (paused) {
            paused = false
            // Resume begins a fresh startup/stall grace period; time spent
            // paused is not evidence of a decoder or transport failure.
            decoderStartupAtMs = null
            lastProgressAtMs = nowMs
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
        const val DEFAULT_STARTED_PROGRESS_MS: Long = 1_500L
        const val DEFAULT_BUFFERED_PROGRESS_MS: Long = 250L
    }
}
