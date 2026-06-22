package com.continuum.app.common.player

import androidx.media3.common.PlaybackException
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackExecutionPlan

class PlaybackRecoveryPlanner {
    fun planForPlayability(
        currentPlan: PlaybackExecutionPlan?,
        reason: Playability,
    ): PlaybackRecoveryAction =
        when (reason) {
            is Playability.UnsupportedDvProfile -> PlaybackRecoveryAction.ServerTranscode(
                errorClass = "unsupported_dolby_vision_profile",
            )
            is Playability.UnsupportedAudioCodec,
            is Playability.UnsupportedChannelCount,
            is Playability.StartupStalled,
            -> serverFallbackFromPlan(currentPlan, "direct_play_failed")
            Playability.Supported -> PlaybackRecoveryAction.None
        }

    fun planForPlayerError(
        currentPlan: PlaybackExecutionPlan?,
        error: PlaybackException,
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
        return serverFallbackFromPlan(currentPlan, errorClass)
    }

    private fun serverFallbackFromPlan(
        currentPlan: PlaybackExecutionPlan?,
        errorClass: String,
    ): PlaybackRecoveryAction {
        val alternateDirect = currentPlan?.fallbacks?.firstOrNull {
            it.delivery == PlaybackDelivery.ORIGINAL_HTTP &&
                it.engine != currentPlan.engine
        }
        if (alternateDirect != null) {
            return PlaybackRecoveryAction.AlternateDirectEngine(
                errorClass = errorClass,
                engine = alternateDirect.engine,
            )
        }
        val fallback = currentPlan?.fallbacks?.firstOrNull {
            it.delivery == PlaybackDelivery.SERVER_REMUX_PROGRESSIVE ||
                it.delivery == PlaybackDelivery.SERVER_REMUX_HLS
        }
        return if (fallback != null) {
            PlaybackRecoveryAction.ServerRemux(errorClass = errorClass)
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
    data class ServerRemux(val errorClass: String) : PlaybackRecoveryAction
    data class ServerTranscode(val errorClass: String) : PlaybackRecoveryAction
}
