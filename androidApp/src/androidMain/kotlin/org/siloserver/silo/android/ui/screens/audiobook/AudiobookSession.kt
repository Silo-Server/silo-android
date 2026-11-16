package org.siloserver.silo.android.ui.screens.audiobook

import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.common.player.AudiobookPlayerViewModel

/**
 * What the audiobook player route asked to play. The fields keep the route's
 * argument types, so the player view model reads the same values here as it
 * would from the route.
 */
data class AudiobookPlaybackRequest(
    val contentId: String,
    val libraryId: String? = null,
    val fileId: String? = null,
    val fromStart: Boolean = false,
    val startPosition: String? = null,
) {
    /**
     * True when opening this request should show the book already playing
     * instead of starting it again: the same book, with no restart, no start
     * offset, and no different file. Resume from the detail page, the mini
     * player, and a downloaded book's row all land here.
     */
    fun continues(active: AudiobookPlaybackRequest): Boolean =
        contentId == active.contentId &&
            !fromStart &&
            startPosition == null &&
            (fileId == null || fileId == active.fileId)

    /** The route that shows the full player for this book without restarting it. */
    fun resumeRoute(): String =
        Route.AudiobookPlayer(contentId = contentId, libraryId = libraryId?.toIntOrNull()).route

    internal fun savedState(): SavedStateHandle = SavedStateHandle(
        mapOf(
            Route.AudiobookPlayer.ARG_CONTENT_ID to contentId,
            "libraryId" to libraryId,
            Route.AudiobookPlayer.ARG_FILE_ID to fileId,
            Route.AudiobookPlayer.ARG_FROM_START to fromStart,
            Route.AudiobookPlayer.ARG_START_POSITION to startPosition,
        ),
    )

    companion object {
        fun from(arguments: Bundle?): AudiobookPlaybackRequest = AudiobookPlaybackRequest(
            contentId = arguments?.getString(Route.AudiobookPlayer.ARG_CONTENT_ID).orEmpty(),
            libraryId = arguments?.getString("libraryId"),
            fileId = arguments?.getString(Route.AudiobookPlayer.ARG_FILE_ID),
            fromStart = arguments?.getBoolean(Route.AudiobookPlayer.ARG_FROM_START, false) ?: false,
            startPosition = arguments?.getString(Route.AudiobookPlayer.ARG_START_POSITION),
        )
    }
}

/** The audiobook playing now, and the player view model that runs it. */
class ActiveAudiobook(
    val request: AudiobookPlaybackRequest,
    val player: AudiobookPlayerViewModel,
    /** The nav back stack entry that started this session. */
    internal val openedBy: String,
) {
    /** The stream already loaded into the engine. Kept with the session, not
     *  the bridge, so a recreated activity doesn't load it again from the
     *  start of the part. */
    internal var preparedUrl: String? = null
}

/**
 * Owns the phone's audiobook playback session, scoped to the activity, so a
 * book keeps playing after its full player closes and the mini player above
 * the tabs can show and control it (iOS `AudioPlaybackStore`).
 *
 * The player view model lives in a store of its own rather than in the
 * player route's back stack entry: closing the route minimizes the player,
 * and only [stop] ends the session. The activity's own teardown ends it too.
 *
 * [identity] names the signed-in server, account and profile. A session ends
 * when it changes, so a book never keeps playing (and reporting progress)
 * under a different account or profile than the one that started it.
 */
class AudiobookSessionViewModel(
    identity: Flow<Any?>,
    private val createPlayer: (SavedStateHandle) -> AudiobookPlayerViewModel,
) : ViewModel() {
    private val _active = MutableStateFlow<ActiveAudiobook?>(null)
    val active: StateFlow<ActiveAudiobook?> = _active.asStateFlow()

    private var store: ViewModelStore? = null
    private var currentIdentity: Any? = null
    private var sessionIdentity: Any? = null

    init {
        viewModelScope.launch {
            identity.collect { id ->
                currentIdentity = id
                if (_active.value != null && id != sessionIdentity) stop()
            }
        }
    }

    /**
     * Returns the player for [request], opened from the back stack entry
     * [entryId]: the running one when that entry started it (the activity was
     * recreated) or the request [continues][AudiobookPlaybackRequest.continues]
     * it, otherwise a new session, after stopping the one before.
     */
    fun open(request: AudiobookPlaybackRequest, entryId: String): AudiobookPlayerViewModel {
        _active.value
            ?.takeIf { it.openedBy == entryId || request.continues(it.request) }
            ?.let { return it.player }
        stop()
        val newStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                createPlayer(request.savedState()) as T
        }
        val player = ViewModelProvider(newStore, factory)[AudiobookPlayerViewModel::class.java]
        store = newStore
        sessionIdentity = currentIdentity
        _active.value = ActiveAudiobook(request, player, openedBy = entryId)
        return player
    }

    /** Ends the session: saves the position, stops the server session, and
     *  clears the player. The engine bridge then releases the media item. */
    fun stop() {
        val current = _active.value ?: return
        _active.value = null
        current.player.flushPosition()
        current.player.stopPlaybackSession()
        store?.clear()
        store = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** The activity's audiobook session, provided above the nav host. */
val LocalAudiobookSession = staticCompositionLocalOf<AudiobookSessionViewModel?> { null }
