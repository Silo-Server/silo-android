package org.siloserver.silo.android.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.siloserver.silo.cast.SiloCastPlaybackRequest

/**
 * Where a Play goes while a TV may be engaged: one decision for every entry
 * point (detail Play, home rails, `silo://play`, Downloads, the detail remote
 * button), mirroring iOS AppRouter's `remotePlaybackInterceptor`.
 *
 * Two plays need the person's say before they happen, as on iOS: replacing a
 * different title the TV is showing, and a download (which can only play on
 * the phone) while a TV is engaged. [SiloCastPlayDialogs] asks both. A
 * pending question holds data only: this router outlives the screen that
 * asked, so the dialogs navigate with the navigation host current when the
 * person answers.
 */
class SiloCastPlayRouter(private val controller: SiloCastController) {
    data class ReplaceChoice(
        val request: SiloCastPlaybackRequest,
        val currentTitle: String,
        val targetName: String,
        /** The phone's player route for the title, used if the TV is gone by the answer. */
        val localRoute: String?,
    )

    data class OfflineChoice(
        val request: SiloCastPlaybackRequest,
        val targetName: String,
        /** The download's player route on the phone. */
        val localRoute: String,
    )

    /** Where an answered question plays the title. */
    sealed interface Destination {
        data object Tv : Destination
        data class Here(val route: String) : Destination
    }

    private val _pendingReplace = MutableStateFlow<ReplaceChoice?>(null)
    val pendingReplace: StateFlow<ReplaceChoice?> = _pendingReplace.asStateFlow()

    private val _pendingOffline = MutableStateFlow<OfflineChoice?>(null)
    val pendingOffline: StateFlow<OfflineChoice?> = _pendingOffline.asStateFlow()

    /**
     * Sends a streaming [request] to the engaged TV. Returns false when no TV
     * is engaged, so the caller plays on the phone. A TV playing a different
     * title asks first; the same title (Resume of what is on) goes straight
     * through. [onLaunched] runs when the request goes out right away.
     * [localRoute] is where the title plays on the phone should the TV be
     * gone when the person answers; null keeps it off the phone.
     */
    fun playStreaming(request: SiloCastPlaybackRequest, localRoute: String?, onLaunched: () -> Unit): Boolean {
        val state = controller.state.value
        if (!state.isEngaged) return false
        val current = state.playbackState?.takeIf { !it.contentId.isNullOrEmpty() }
        if (current != null && current.contentId != request.contentId) {
            _pendingReplace.value = ReplaceChoice(
                request = request,
                currentTitle = current.title,
                targetName = state.targetName,
                localRoute = localRoute,
            )
            return true
        }
        if (controller.launchOnConnectedTarget(request)) onLaunched()
        return true
    }

    /**
     * A download plays only on the phone. With a TV engaged, asks whether to
     * play it here (at [localRoute]) or stream the same title on the TV.
     */
    fun playOffline(request: SiloCastPlaybackRequest, localRoute: String, playHere: () -> Unit) {
        val state = controller.state.value
        if (!state.isEngaged) {
            playHere()
            return
        }
        _pendingOffline.value = OfflineChoice(
            request = request,
            targetName = state.targetName,
            localRoute = localRoute,
        )
    }

    fun confirmReplace(): Destination? {
        val choice = _pendingReplace.getAndUpdate { null } ?: return null
        return sendToTv(choice.request, fallbackRoute = choice.localRoute)
    }

    fun dismissReplace() {
        _pendingReplace.value = null
    }

    fun sendOfflineToTv(): Destination? {
        val choice = _pendingOffline.getAndUpdate { null } ?: return null
        return sendToTv(choice.request, fallbackRoute = choice.localRoute)
    }

    fun playOfflineHere(): Destination? =
        _pendingOffline.getAndUpdate { null }?.let { Destination.Here(it.localRoute) }

    fun dismissOffline() {
        _pendingOffline.value = null
    }

    // The TV can drop while the question is up: play here instead, as Play
    // would with no TV, rather than losing the request.
    private fun sendToTv(request: SiloCastPlaybackRequest, fallbackRoute: String?): Destination? = when {
        controller.launchOnConnectedTarget(request) -> Destination.Tv
        fallbackRoute != null -> Destination.Here(fallbackRoute)
        else -> null
    }

    private val SiloCastControllerState.targetName: String
        get() = connectedTarget?.name ?: "the TV"
}
