package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.FollowedSession
import org.siloserver.silo.common.player.PlaybackQualityPair
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.isPlaybackSessionEnded
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.network.ApiResult

/**
 * A deferred replan keeps the selected subtitle ordinal, and a network move's
 * quality pair, until its recovery can run.
 */
internal data class MobileRecoveryReplan(
    val classification: String,
    val notice: String,
    val subtitleTrackIndexOverride: Int? = null,
    val qualityPair: PlaybackQualityPair? = null,
) {
    /**
     * Whether this replan still applies when it is re-driven. A network move
     * does not once the session stops following the settings ([followed] is
     * null: the viewer picked a fixed rung while it waited), already plays its
     * pair, or now follows an Auto pick that a settings pair with a fixed
     * resolution would undo; the restarted follower re-plans that one.
     */
    fun stillWanted(followed: FollowedSession?): Boolean {
        val move = qualityPair ?: return true
        val current = followed ?: return false
        if (current.pair == move) return false
        return !current.autoPicked || move.qualityPreference == PLAYBACK_QUALITY_AUTO
    }

    val shouldQueue: Boolean
        get() = classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS ||
            classification == "subtitle_embedded_failed"

    /**
     * A failed subtitle replan keeps the current video playing, except when
     * the server ended the session (410 `playback_session_ended`): the player
     * then leaves instead.
     */
    fun isNonfatalFailure(result: ApiResult<VideoSessionStartV3>): Boolean =
        classification == "subtitle_embedded_failed" &&
            result !is ApiResult.Success &&
            !result.isPlaybackSessionEnded()
}
