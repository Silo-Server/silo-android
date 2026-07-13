package org.siloserver.silo.common.player.video

/**
 * Detects the TV-specific failure where audio and the playback clock resume but
 * the video decoder/surface emits no new frames. Recovery is deliberately
 * bounded: one small non-zero seek, then one codec/surface re-prepare.
 */
class PostResumeVideoStallDetector(
    private val detectionGraceMs: Long = DEFAULT_DETECTION_GRACE_MS,
    private val verificationGraceMs: Long = DEFAULT_VERIFICATION_GRACE_MS,
    private val minimumPositionAdvanceMs: Long = DEFAULT_MINIMUM_POSITION_ADVANCE_MS,
    private val endGuardMs: Long = DEFAULT_END_GUARD_MS,
) {
    sealed interface Signal {
        data object SeekBack : Signal
        data object Reprepare : Signal
        data class Recovered(val correctionId: String) : Signal
        data class Failed(val correctionId: String) : Signal
    }

    private enum class Stage { IDLE, WATCHING_RESUME, VERIFYING_SEEK, VERIFYING_REPREPARE, EXHAUSTED }

    private var sessionKey: String? = null
    private var firstFrameRendered = false
    private var pausedAfterFirstFrame = false
    private var pausedDuringRecovery = false
    private var stage = Stage.IDLE
    private var stageStartedAtMs = 0L
    private var baselinePositionMs = 0L
    private var baselineRenderedCount = 0

    fun onMounted(sessionKey: String) {
        if (this.sessionKey == sessionKey) return
        this.sessionKey = sessionKey
        firstFrameRendered = false
        pausedAfterFirstFrame = false
        pausedDuringRecovery = false
        stage = Stage.IDLE
        stageStartedAtMs = 0L
        baselinePositionMs = 0L
        baselineRenderedCount = 0
    }

    fun onFirstFrameRendered() {
        firstFrameRendered = true
    }

    fun onIsPlayingChanged(
        sessionKey: String,
        isPlaying: Boolean,
        nowMs: Long,
        currentPositionMs: Long,
        renderedOutputBufferCount: Int?,
    ) {
        if (sessionKey != this.sessionKey || !firstFrameRendered || stage == Stage.EXHAUSTED) return
        if (!isPlaying) {
            if (stage == Stage.IDLE) {
                pausedAfterFirstFrame = true
            } else {
                pausedDuringRecovery = true
            }
            return
        }
        if (pausedAfterFirstFrame && stage == Stage.IDLE && renderedOutputBufferCount != null) {
            pausedAfterFirstFrame = false
            beginStage(Stage.WATCHING_RESUME, nowMs, currentPositionMs, renderedOutputBufferCount)
        } else if (pausedDuringRecovery && renderedOutputBufferCount != null) {
            pausedDuringRecovery = false
            beginStage(stage, nowMs, currentPositionMs, renderedOutputBufferCount)
        }
    }

    fun sample(
        sessionKey: String,
        nowMs: Long,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        isReady: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
        renderedOutputBufferCount: Int?,
    ): Signal? {
        if (sessionKey != this.sessionKey || renderedOutputBufferCount == null || stage == Stage.IDLE ||
            stage == Stage.EXHAUSTED
        ) return null
        if (!playWhenReady || !isPlaying || !isReady) return null
        if (durationMs > 0 && durationMs - currentPositionMs <= endGuardMs) return null

        if (renderedOutputBufferCount > baselineRenderedCount) {
            val recovered = when (stage) {
                Stage.VERIFYING_SEEK -> "client_post_resume_video_recovery_v1"
                Stage.VERIFYING_REPREPARE -> "client_surface_recovery_v1"
                else -> null
            }
            stage = Stage.IDLE
            return recovered?.let(Signal::Recovered)
        }
        if (currentPositionMs - baselinePositionMs < minimumPositionAdvanceMs) return null

        val elapsed = nowMs - stageStartedAtMs
        return when (stage) {
            Stage.WATCHING_RESUME -> if (elapsed >= detectionGraceMs) {
                beginStage(Stage.VERIFYING_SEEK, nowMs, currentPositionMs, renderedOutputBufferCount)
                Signal.SeekBack
            } else null
            Stage.VERIFYING_SEEK -> if (elapsed >= verificationGraceMs) {
                beginStage(Stage.VERIFYING_REPREPARE, nowMs, currentPositionMs, renderedOutputBufferCount)
                Signal.Reprepare
            } else null
            Stage.VERIFYING_REPREPARE -> if (elapsed >= verificationGraceMs) {
                stage = Stage.EXHAUSTED
                Signal.Failed("client_surface_recovery_v1")
            } else null
            Stage.IDLE, Stage.EXHAUSTED -> null
        }
    }

    private fun beginStage(stage: Stage, nowMs: Long, positionMs: Long, renderedCount: Int) {
        this.stage = stage
        pausedDuringRecovery = false
        stageStartedAtMs = nowMs
        baselinePositionMs = positionMs
        baselineRenderedCount = renderedCount
    }

    companion object {
        const val DEFAULT_DETECTION_GRACE_MS = 5_000L
        const val DEFAULT_VERIFICATION_GRACE_MS = 5_000L
        const val DEFAULT_MINIMUM_POSITION_ADVANCE_MS = 750L
        const val DEFAULT_END_GUARD_MS = 5_000L
        const val SEEK_BACK_MS = 250L
    }
}
