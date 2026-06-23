package com.continuum.app.common.player

import androidx.media3.common.PlaybackException
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackExecutionPlan

class PlaybackRecoveryPlanner {
    fun planForPlayability(
        currentPlan: PlaybackExecutionPlan?,
        reason: Playability,
        attemptedEngines: Set<PlaybackEngineKind> = emptySet(),
    ): PlaybackRecoveryAction =
        when (reason) {
            is Playability.UnsupportedDvProfile -> PlaybackRecoveryAction.ServerTranscode(
                errorClass = "unsupported_dolby_vision_profile",
            )
            // An unsupported audio codec / channel layout cannot be fixed by a
            // REMUX — REMUX copies the audio stream verbatim, re-sending the exact
            // codec the sink just rejected. Force a transcode path (audio is
            // re-encoded) by excluding remux from the ladder.
            is Playability.UnsupportedAudioCodec,
            is Playability.UnsupportedChannelCount,
            -> serverFallbackFromPlan(
                currentPlan,
                errorClass = "direct_play_failed",
                attemptedEngines = attemptedEngines,
                allowRemux = false,
            )
            is Playability.StartupStalled,
            -> serverFallbackFromPlan(currentPlan, "direct_play_failed", attemptedEngines)
            Playability.Supported -> PlaybackRecoveryAction.None
        }

    fun planForPlayerError(
        currentPlan: PlaybackExecutionPlan?,
        error: PlaybackException,
        attemptedEngines: Set<PlaybackEngineKind> = emptySet(),
    ): PlaybackRecoveryAction {
        val errorClass = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> "decoder"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            -> "http"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "source"
            else -> "unknown"
        }
        return serverFallbackFromPlan(currentPlan, errorClass, attemptedEngines)
    }

    /**
     * Recovery for a failed client-side engine switch. Prefers another direct
     * engine (the switch failing doesn't mean the content needs the server),
     * then the plan's remux/transcode ladder — rather than blindly forcing a
     * remux that a transcode-only source can't satisfy.
     */
    fun planForEngineSwitchFailure(
        currentPlan: PlaybackExecutionPlan?,
        attemptedEngines: Set<PlaybackEngineKind> = emptySet(),
    ): PlaybackRecoveryAction =
        serverFallbackFromPlan(currentPlan, "engine_switch_failed", attemptedEngines)

    private fun serverFallbackFromPlan(
        currentPlan: PlaybackExecutionPlan?,
        errorClass: String,
        attemptedEngines: Set<PlaybackEngineKind> = emptySet(),
        allowRemux: Boolean = true,
    ): PlaybackRecoveryAction {
        // Exclude engines we've already been on, not just the current one — a
        // bare `engine != currentPlan.engine` check lets MEDIA3_DIRECT and
        // MPV_DIRECT ping-pong indefinitely when the plan lists both.
        val alternateDirect = currentPlan?.fallbacks?.firstOrNull {
            it.delivery == PlaybackDelivery.ORIGINAL_HTTP &&
                it.engine != currentPlan.engine &&
                it.engine !in attemptedEngines
        }
        if (alternateDirect != null) {
            return PlaybackRecoveryAction.AlternateDirectEngine(
                errorClass = errorClass,
                engine = alternateDirect.engine,
            )
        }
        val remux = if (allowRemux) {
            currentPlan?.fallbacks?.firstOrNull {
                it.delivery == PlaybackDelivery.SERVER_REMUX_PROGRESSIVE ||
                    it.delivery == PlaybackDelivery.SERVER_REMUX_HLS
            }
        } else {
            null
        }
        return if (remux != null) {
            PlaybackRecoveryAction.ServerRemux(
                errorClass = errorClass,
                delivery = remux.delivery,
            )
        } else {
            PlaybackRecoveryAction.ServerTranscode(errorClass = errorClass)
        }
    }
}

sealed interface PlaybackRecoveryAction {
    data object None : PlaybackRecoveryAction
    data class AlternateDirectEngine(
        val errorClass: String,
        val engine: PlaybackEngineKind,
    ) : PlaybackRecoveryAction
    data class ServerRemux(
        val errorClass: String,
        // The remux delivery the planner actually chose (progressive vs HLS), so
        // downstream routing/telemetry don't silently collapse both into HLS.
        val delivery: PlaybackDelivery = PlaybackDelivery.SERVER_REMUX_HLS,
    ) : PlaybackRecoveryAction
    data class ServerTranscode(val errorClass: String) : PlaybackRecoveryAction
}
