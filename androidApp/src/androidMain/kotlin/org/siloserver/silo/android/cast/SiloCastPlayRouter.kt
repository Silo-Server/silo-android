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
 * the phone) while a TV is engaged. [SiloCastPlayDialogs] asks both.
 */
class SiloCastPlayRouter(private val controller: SiloCastController) {
    data class ReplaceChoice(
        val request: SiloCastPlaybackRequest,
        val currentTitle: String,
        val targetName: String,
        val onLaunched: () -> Unit,
    )

    data class OfflineChoice(
        val request: SiloCastPlaybackRequest,
        val targetName: String,
        val playHere: () -> Unit,
        val onLaunched: () -> Unit,
    )

    private val _pendingReplace = MutableStateFlow<ReplaceChoice?>(null)
    val pendingReplace: StateFlow<ReplaceChoice?> = _pendingReplace.asStateFlow()

    private val _pendingOffline = MutableStateFlow<OfflineChoice?>(null)
    val pendingOffline: StateFlow<OfflineChoice?> = _pendingOffline.asStateFlow()

    /**
     * Sends a streaming [request] to the engaged TV. Returns false when no TV
     * is engaged, so the caller plays on the phone. A TV playing a different
     * title asks first; the same title (Resume of what is on) goes straight
     * through. [onLaunched] runs once the request is on its way to the TV.
     */
    fun playStreaming(request: SiloCastPlaybackRequest, onLaunched: () -> Unit): Boolean {
        val state = controller.state.value
        if (!state.isEngaged) return false
        val current = state.playbackState?.takeIf { !it.contentId.isNullOrEmpty() }
        if (current != null && current.contentId != request.contentId) {
            _pendingReplace.value = ReplaceChoice(
                request = request,
                currentTitle = current.title,
                targetName = state.targetName,
                onLaunched = onLaunched,
            )
            return true
        }
        launch(request, onLaunched)
        return true
    }

    /**
     * A download plays only on the phone. With a TV engaged, asks whether to
     * play it here or stream the same title on the TV instead.
     */
    fun playOffline(request: SiloCastPlaybackRequest, playHere: () -> Unit, onLaunched: () -> Unit) {
        val state = controller.state.value
        if (!state.isEngaged) {
            playHere()
            return
        }
        _pendingOffline.value = OfflineChoice(
            request = request,
            targetName = state.targetName,
            playHere = playHere,
            onLaunched = onLaunched,
        )
    }

    fun confirmReplace() {
        val choice = _pendingReplace.getAndUpdate { null } ?: return
        launch(choice.request, choice.onLaunched)
    }

    fun dismissReplace() {
        _pendingReplace.value = null
    }

    fun sendOfflineToTv() {
        val choice = _pendingOffline.getAndUpdate { null } ?: return
        launch(choice.request, choice.onLaunched)
    }

    fun playOfflineHere() {
        _pendingOffline.getAndUpdate { null }?.playHere?.invoke()
    }

    fun dismissOffline() {
        _pendingOffline.value = null
    }

    private fun launch(request: SiloCastPlaybackRequest, onLaunched: () -> Unit) {
        if (controller.launchOnConnectedTarget(request)) onLaunched()
    }

    private val SiloCastControllerState.targetName: String
        get() = connectedTarget?.name ?: "the TV"
}
