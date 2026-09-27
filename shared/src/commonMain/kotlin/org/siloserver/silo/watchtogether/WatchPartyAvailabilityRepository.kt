package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.PlaybackCapabilitiesV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Discovers whether this server and profile can use Watch Party, from the room
 * and playback capability documents, and caches the answer for the captured
 * authority. A change of server, account, or profile discards the cache. A
 * failed probe never downgrades a known answer: it keeps the last result for
 * this authority and only reports [WatchPartyAvailability.ProbeFailed] when
 * nothing is known yet.
 */
class WatchPartyAvailabilityRepository(
    private val roomCapabilities: suspend (AuthScopeSnapshot) -> ApiResult<WatchTogetherCapabilitiesV2>,
    private val playbackCapabilities: suspend (AuthScopeSnapshot) -> ApiResult<PlaybackCapabilitiesV2>,
    private val authScopeProvider: suspend () -> AuthScopeSnapshot?,
) {
    private val mutex = Mutex()
    private var cachedOwner: AuthScopeSnapshot? = null
    private val _availability = MutableStateFlow<WatchPartyAvailability?>(null)

    /** The last known answer for the current authority, or null before the first probe. */
    val availability: StateFlow<WatchPartyAvailability?> = _availability.asStateFlow()

    /** The features of the current authority, when Watch Party is available. */
    val features: WatchPartyFeatures?
        get() = (_availability.value as? WatchPartyAvailability.Available)?.features

    /**
     * Probe unless a definite answer for the current authority is cached.
     * [force] probes again even then (the user pressed Retry, or the app
     * returned to the foreground).
     */
    suspend fun refresh(force: Boolean = false): WatchPartyAvailability = mutex.withLock {
        val scope = authScopeProvider()
            ?: return@withLock WatchPartyAvailability.ProbeFailed(NOT_SIGNED_IN).also { forgetLocked() }
        if (!sameOwner(cachedOwner, scope)) {
            cachedOwner = scope
            _availability.value = null
        }
        val cached = _availability.value
        if (!force && cached != null && cached !is WatchPartyAvailability.ProbeFailed) return@withLock cached
        val probed = probe(scope)
        if (!sameOwner(authScopeProvider(), scope)) {
            return@withLock WatchPartyAvailability.ProbeFailed(IDENTITY_CHANGED)
        }
        val published = if (probed is WatchPartyAvailability.ProbeFailed && cached != null &&
            cached !is WatchPartyAvailability.ProbeFailed
        ) {
            cached
        } else {
            probed
        }
        _availability.value = published
        published
    }

    /** Drop the cached answer, for example on sign-out. */
    suspend fun forget() = mutex.withLock { forgetLocked() }

    private fun forgetLocked() {
        cachedOwner = null
        _availability.value = null
    }

    private suspend fun probe(scope: AuthScopeSnapshot): WatchPartyAvailability {
        val room = when (val r = roomCapabilities(scope)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return WatchPartyAvailability.ProbeFailed(r.message.ifBlank { r.error })
            is ApiResult.NetworkError -> return WatchPartyAvailability.ProbeFailed(r.exception.message ?: NETWORK)
        }
        if (room.state != "available") return watchPartyAvailability(room, UNAVAILABLE_PLAYBACK)
        val playback = when (val r = playbackCapabilities(scope)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return WatchPartyAvailability.ProbeFailed(r.message.ifBlank { r.error })
            is ApiResult.NetworkError -> return WatchPartyAvailability.ProbeFailed(r.exception.message ?: NETWORK)
        }
        return watchPartyAvailability(room, playback)
    }

    private fun sameOwner(a: AuthScopeSnapshot?, b: AuthScopeSnapshot?): Boolean =
        a != null && b != null && a.isSameIdentityAs(b) && a.profileId == b.profileId

    private companion object {
        const val NOT_SIGNED_IN = "not_signed_in"
        const val IDENTITY_CHANGED = "identity_changed"
        const val NETWORK = "network_error"
        val UNAVAILABLE_PLAYBACK = PlaybackCapabilitiesV2(
            state = "unavailable",
            allowed = false,
            protocolVersions = emptyList(),
            features = emptyList(),
        )
    }
}
