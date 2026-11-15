package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.isPlaybackSessionEnded
import org.siloserver.silo.model.playback.SUBTITLE_SELECTION_FAILURE_CLASSIFICATIONS
import org.siloserver.silo.network.ApiResult

/** A deferred replan keeps the selected subtitle ordinal until its recovery can run. */
internal data class MobileRecoveryReplan(
    val classification: String,
    val notice: String,
    val subtitleTrackIndexOverride: Int? = null,
) {
    val shouldQueue: Boolean
        get() = classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS ||
            classification in SUBTITLE_SELECTION_FAILURE_CLASSIFICATIONS

    /**
     * A failed subtitle replan keeps the current video playing, except when
     * the server ended the session (410 `playback_session_ended`): the player
     * then leaves instead.
     */
    fun isNonfatalFailure(result: ApiResult<VideoSessionStartV3>): Boolean =
        classification in SUBTITLE_SELECTION_FAILURE_CLASSIFICATIONS &&
            result !is ApiResult.Success &&
            !result.isPlaybackSessionEnded()
}
