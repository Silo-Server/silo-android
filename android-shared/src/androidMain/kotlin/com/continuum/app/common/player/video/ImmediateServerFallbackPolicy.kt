package com.continuum.app.common.player.video

import com.continuum.app.common.player.PlaybackSessionManager
import com.continuum.app.model.playback.PlaybackExecutionPlan

/**
 * Server-supplied route blockers the client must honor by immediately starting a
 * server fallback at session start (the launch policy hasn't validated these
 * routes on-device). Centralized so the wire strings live in ONE typed place
 * instead of being re-typed in every starter / view-model.
 */
object PlaybackRouteBlockers {
    const val DOLBY_VISION_PROFILE_7_NOT_LAUNCH_CLAIMED = "dolby_vision_profile_7_not_launch_claimed"
    const val BITMAP_SUBTITLE_ROUTE_NOT_VALIDATED = "bitmap_subtitle_route_not_validated"
}

/**
 * The [PlaybackSessionManager.TranscodeMode] to fall back to immediately at
 * start when the plan carries a launch blocker the client can't honor directly,
 * or null when the resolved route can be attempted as-is. Single source of truth
 * shared by the mobile/TV starters and the mobile player view-model.
 */
fun PlaybackExecutionPlan.immediateServerFallbackMode(): PlaybackSessionManager.TranscodeMode? {
    val blockers = capabilities.blockers.toSet()
    if (blockers.isEmpty()) return null
    return when {
        PlaybackRouteBlockers.DOLBY_VISION_PROFILE_7_NOT_LAUNCH_CLAIMED in blockers ->
            PlaybackSessionManager.TranscodeMode.FULL
        PlaybackRouteBlockers.BITMAP_SUBTITLE_ROUTE_NOT_VALIDATED in blockers ->
            PlaybackSessionManager.TranscodeMode.FULL
        else -> PlaybackSessionManager.TranscodeMode.FULL
    }
}
