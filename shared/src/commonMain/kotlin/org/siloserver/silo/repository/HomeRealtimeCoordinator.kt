package org.siloserver.silo.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.HomeRealtimeClient
import org.siloserver.silo.network.HomeRealtimeEvent
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.homeRefreshTrigger
import org.siloserver.silo.network.GenericEventsBackoff
import org.siloserver.silo.network.GenericEventsClose
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.genericEventsForIdentity
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.matches

/**
 * Owns the live-home websocket lifecycle (Apple realtime-updates spec):
 * reconnect with capped backoff, drop foreign-profile user_state events, and
 * coalesce bursts (foreign playback progress every ~10s, catalog scan storms)
 * behind a trailing debounce. Collectors ([refreshSignals]) get one signal
 * per quiet window and refetch Home over REST — the socket never carries
 * authoritative data.
 *
 * The foreground starters own [connect]'s scope: connected only while the
 * app is foregrounded, reconnected on profile switch.
 */
class HomeRealtimeCoordinator(
    private val client: HomeRealtimeClient,
    private val tokenManager: TokenManager,
    /**
     * Server-reported access changes. Either events socket may be the one that
     * sees the change (each runs its own access check), so Home listens here
     * rather than only to its own frames. The socket's 4001 close then makes
     * [connect] reconnect with a freshly minted ticket.
     */
    private val accessChanges: AccessChangeSignals? = null,
    private val identityTransitions: IdentityTransitionBarrier? = null,
    private val recoveryFactory: () -> GenericEventsBackoff = { GenericEventsBackoff() },
) {
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Debounced refetch signal — collect and call the Home refresh. */
    @OptIn(FlowPreview::class)
    val refreshSignals: Flow<Unit> = (accessChanges?.let { merge(triggers, it.changes) } ?: triggers)
        .debounce(DEBOUNCE_MS)

    fun connect(scope: CoroutineScope): Job = scope.launch {
        genericEventsForIdentity(identityTransitions) {
            val recovery = recoveryFactory()
            val owner = tokenManager.snapshotCurrentScope()
            while (true) {
                if (owner != null && !owner.matches(tokenManager.snapshotCurrentScope(), OwnerPolicy.PROFILE)) return@genericEventsForIdentity
                var closed = GenericEventsClose()
                try {
                    client.connect().collect { event ->
                        if (owner != null && !owner.matches(tokenManager.snapshotCurrentScope(), OwnerPolicy.PROFILE)) return@collect
                        if (event is HomeRealtimeEvent.Closed) closed = event.recovery
                        if (homeRefreshTrigger(event, tokenManager.getProfileId())) triggers.tryEmit(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed attempt follows the same ladder as an unacknowledged close.
                }
                delay(recovery.disconnected(closed))
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
