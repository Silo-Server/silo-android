package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.model.shuffle.Shuffle
import org.siloserver.silo.model.shuffle.ShuffleCapability
import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ShufflesV2Api

/**
 * Server-gated Shuffle capability, and the one place entry points start a
 * shuffle from.
 *
 * Entry points start hidden; a successful `/api/v2/shuffles/capabilities`
 * probe controls visibility per scope kind, transient failures keep the
 * previous value, and reset hides the surface before a server or profile
 * switch can reuse stale state. A server without shuffles answers `404`,
 * which hides it too.
 */
class ShuffleFeatureStore(
    private val api: ShufflesV2Api,
) {
    private val _capability = MutableStateFlow(ShuffleCapability())
    val capability: StateFlow<ShuffleCapability> = _capability.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        val next = when (val result = api.capability()) {
            is ApiResult.Success -> result.data
            // The server answered: an older server has no shuffles.
            is ApiResult.Error -> if (result.code == 404) ShuffleCapability() else return
            is ApiResult.NetworkError -> return
        }
        if (refreshGeneration == generation) _capability.value = next
    }

    fun reset() {
        generation += 1
        _capability.value = ShuffleCapability()
        started = null
    }

    // The shuffle the last start returned, until its player takes it. The
    // player then knows the first pick's `next` without another read.
    @kotlin.concurrent.Volatile
    private var started: Shuffle? = null

    /** Hands the player the shuffle [start] returned for [shuffleId], once. */
    fun takeStarted(shuffleId: String): Shuffle? =
        started?.takeIf { it.id == shuffleId }?.also { started = null }

    /** The playback state of shuffle [shuffleId], seeded with what [start] returned. */
    fun playback(shuffleId: String): org.siloserver.silo.playback.ShufflePlayback =
        org.siloserver.silo.playback.ShufflePlayback(api, shuffleId, takeStarted(shuffleId))

    /**
     * Starts a shuffle over a scope. On success play `current` from the
     * beginning; on failure show [ShuffleStart.Failed.message].
     */
    suspend fun start(kind: ShuffleScopeKind, id: String): ShuffleStart =
        when (val result = api.create(kind, id)) {
            is ApiResult.Success -> {
                started = result.data
                ShuffleStart.Started(result.data)
            }
            is ApiResult.Error ->
                ShuffleStart.Failed(if (result.code == 409) NOTHING_PLAYABLE else START_FAILED)
            is ApiResult.NetworkError -> ShuffleStart.Failed(START_FAILED)
        }

    companion object {
        const val NOTHING_PLAYABLE = "Nothing here can be played."
        const val START_FAILED = "Couldn't start shuffle."
    }
}

sealed interface ShuffleStart {
    data class Started(val shuffle: Shuffle) : ShuffleStart
    data class Failed(val message: String) : ShuffleStart
}
