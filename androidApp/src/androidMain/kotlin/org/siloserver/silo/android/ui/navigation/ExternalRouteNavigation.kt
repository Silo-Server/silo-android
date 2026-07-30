package org.siloserver.silo.android.ui.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.siloserver.silo.android.ui.screens.player.MobilePlayerRouteIntent
import org.siloserver.silo.android.ui.screens.player.MobilePlayerRouteTarget
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs

/** A single external-navigation delivery, distinct even when its route repeats. */
class ExternalRouteRequest internal constructor(
    val generation: Long,
    val route: String,
)

internal class ExternalRouteRequestFactory {
    private var latestGeneration = 0L

    fun create(route: String): ExternalRouteRequest =
        ExternalRouteRequest(
            generation = ++latestGeneration,
            route = route,
        )
}

internal fun clearConsumedExternalRouteRequest(
    pendingRequest: ExternalRouteRequest?,
    consumedRequest: ExternalRouteRequest,
): ExternalRouteRequest? =
    if (pendingRequest?.generation == consumedRequest.generation) null else pendingRequest

internal fun shouldReplaceCurrentPlayer(
    currentDestinationRoute: String?,
    targetRoute: String,
): Boolean =
    currentDestinationRoute == Route.Player.ROUTE && targetRoute.startsWith("player/")

/** Parses the canonical in-app player route carried by an external request. */
internal fun playerRouteIntentOrNull(route: String): MobilePlayerRouteIntent? {
    if (!route.startsWith("player/")) return null
    val contentId = route
        .substringAfter("player/")
        .substringBefore('?')
        .takeIf { it.isNotBlank() }
        ?: return null
    val query = route
        .substringAfter('?', "")
        .split('&')
        .filter(String::isNotBlank)
        .associate { part -> part.substringBefore('=') to part.substringAfter('=', "") }
    // This provider describes solo playback only. A room-scoped target must be
    // handled by Watch Together even if every media choice happens to match.
    if ("roomId" in query) return null

    val fileId = query["fileId"]?.toIntOrNull()
    if ("fileId" in query && (fileId == null || fileId <= 0)) return null
    val quality = query[VideoPlayerRouteArgs.QUALITY]
        ?.let(VideoPlayerRouteArgs::normalizeQuality)
    if (VideoPlayerRouteArgs.QUALITY in query && quality == null) return null
    val audioTrackIndex = query["audioTrackIndex"]?.toIntOrNull()
    if ("audioTrackIndex" in query && (audioTrackIndex == null || audioTrackIndex < 0)) return null
    val subtitleTrackIndex = query["subtitleTrackIndex"]?.toIntOrNull()
    if ("subtitleTrackIndex" in query && (subtitleTrackIndex == null || subtitleTrackIndex < -1)) return null
    val resumePositionSeconds = query[VideoPlayerRouteArgs.RESUME_POSITION]
        ?.let(VideoPlayerRouteArgs::parseResumePosition)
    if (VideoPlayerRouteArgs.RESUME_POSITION in query && resumePositionSeconds == null) return null

    return MobilePlayerRouteIntent(
        contentId = contentId,
        fileId = fileId,
        quality = quality,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        resumePositionSeconds = resumePositionSeconds,
        fileIsExplicit = "fileId" in query,
        qualityIsExplicit = VideoPlayerRouteArgs.QUALITY in query,
        audioTrackIsExplicit = "audioTrackIndex" in query,
        subtitleTrackIsExplicit = "subtitleTrackIndex" in query,
    )
}

/**
 * Tests an incoming route against both the current route intent and live
 * playback values. An omitted parameter remains exact only while the player is
 * still using automatic selection; an explicit parameter compares to the live
 * resolved value.
 */
internal fun MobilePlayerRouteIntent.matches(target: MobilePlayerRouteTarget): Boolean =
    contentId == target.contentId &&
        resumePositionSeconds == target.resumePositionSeconds &&
        optionalTargetMatches(
            requestedIsExplicit = fileIsExplicit,
            requestedValue = fileId,
            currentIsExplicit = target.intent.fileIsExplicit,
            liveValue = target.fileId,
        ) &&
        optionalTargetMatches(
            requestedIsExplicit = qualityIsExplicit,
            requestedValue = quality,
            currentIsExplicit = target.intent.qualityIsExplicit,
            liveValue = target.quality,
        ) &&
        optionalTargetMatches(
            requestedIsExplicit = audioTrackIsExplicit,
            requestedValue = audioTrackIndex,
            currentIsExplicit = target.intent.audioTrackIsExplicit,
            liveValue = target.audioTrackIndex,
        ) &&
        optionalTargetMatches(
            requestedIsExplicit = subtitleTrackIsExplicit,
            requestedValue = subtitleTrackIndex,
            currentIsExplicit = target.intent.subtitleTrackIsExplicit,
            liveValue = target.subtitleTrackIndex,
        )

private fun <T> optionalTargetMatches(
    requestedIsExplicit: Boolean,
    requestedValue: T?,
    currentIsExplicit: Boolean,
    liveValue: T?,
): Boolean = if (requestedIsExplicit) {
    requestedValue != null && requestedValue == liveValue
} else {
    !currentIsExplicit
}

private val preAuthenticationDestinationRoutes = setOf(
    Route.Login.route,
    Route.ServerSetup.route,
    Route.ServerList.route,
    Route.Setup.route,
    Route.Signup.route,
    Route.ProfileSelection.route,
    Route.CreateProfile.route,
    Route.EditProfile.ROUTE,
    Route.PairDevice.ROUTE,
    Route.InviteClaim.ROUTE,
    Route.OnboardingTour.route,
)

/**
 * Waits until [pendingExternalRoute] is allowed from the current graph, then
 * delivers it exactly once. [first] ends the back-stack subscription before
 * [navigate] can emit the destination it just added; navigating from inside a
 * long-lived collector otherwise feeds that new entry back into the same route.
 */
internal suspend fun consumeExternalRouteOnce(
    pendingExternalRoute: ExternalRouteRequest?,
    currentDestinationRoutes: Flow<String?>,
    isAlreadyAtRoute: (String) -> Boolean = { false },
    navigate: (String) -> Unit,
    onConsumed: (ExternalRouteRequest) -> Unit,
) {
    val request = pendingExternalRoute ?: return
    val route = request.route.takeIf { it.isNotBlank() } ?: return
    val isPreAuthenticationTarget = route.startsWith("invite_claim")

    currentDestinationRoutes.first { currentRoute ->
        isPreAuthenticationTarget || currentRoute !in preAuthenticationDestinationRoutes
    }
    if (!isAlreadyAtRoute(route)) {
        navigate(route)
    }
    onConsumed(request)
}
