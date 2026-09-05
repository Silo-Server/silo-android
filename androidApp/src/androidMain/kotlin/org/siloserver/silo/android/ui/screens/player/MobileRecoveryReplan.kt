package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.network.ApiResult

/** A deferred replan keeps the selected subtitle ordinal until its recovery can run. */
internal data class MobileRecoveryReplan(
    val classification: String,
    val notice: String,
    val subtitleTrackIndexOverride: Int? = null,
) {
    val shouldQueue: Boolean
        get() = classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS ||
            classification == "subtitle_embedded_failed"

    fun isNonfatalFailure(result: ApiResult<VideoSessionStartV3>): Boolean =
        classification == "subtitle_embedded_failed" &&
            result !is ApiResult.Success
}
