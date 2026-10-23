package org.siloserver.silo.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.api.NotificationsApi
import org.siloserver.silo.repository.HomeRealtimeCoordinator
import org.siloserver.silo.repository.NotificationsRepository
import kotlin.test.*

/** Actual common callers, with the same real barrier transition as inactive server removal. */
@OptIn(ExperimentalCoroutinesApi::class)
class GenericEventsGenerationTest {
    @Test fun inactiveRemovalRebasesConnectedHome() = inactiveRemoval(false, false)
    @Test fun inactiveRemovalRebasesRetryingHome() = inactiveRemoval(false, true)
    @Test fun inactiveRemovalRebasesConnectedNotifications() = inactiveRemoval(true, false)
    @Test fun inactiveRemovalRebasesRetryingNotifications() = inactiveRemoval(true, true)

    private fun inactiveRemoval(notifications: Boolean, pendingRetry: Boolean) = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = AuthScopeSnapshot(
                "active", "profile", "https://example.invalid", "pin", identityGeneration = barrier.generation.value,
            )
        }
        val starts = mutableListOf<Long>()
        var active = 0
        var peak = 0
        suspend fun connection(closed: suspend () -> Unit) {
            starts += barrier.generation.value
            active++
            peak = maxOf(peak, active)
            try { if (pendingRetry) closed() else awaitCancellation() } finally { active-- }
        }
        val job = if (notifications) {
            val client = object : NotificationsRealtimeClient {
                override fun connect() = flow<NotificationRealtimeEvent> {
                    connection { emit(NotificationRealtimeEvent.Closed()) }
                }
            }
            NotificationsRepository(UnusedNotificationsApi, { client }, tokens = tokens,
                identityTransitions = barrier, recoveryFactory = { GenericEventsBackoff { 0.0 } })
                .connectRealtime(backgroundScope)
        } else {
            val client = object : HomeRealtimeClient {
                override fun connect() = flow<HomeRealtimeEvent> {
                    connection { emit(HomeRealtimeEvent.Closed()) }
                }
            }
            HomeRealtimeCoordinator(client, tokens, identityTransitions = barrier,
                recoveryFactory = { GenericEventsBackoff { 0.0 } }).connect(backgroundScope)
        }
        runCurrent()
        assertEquals(listOf(0L), starts)
        val release = CompletableDeferred<Unit>()
        val mutation = backgroundScope.launch {
            barrier.changing(IdentityTransitionKind.SERVER_REMOVE,
                target = { IdentityTransitionTarget("inactive", affectsCurrentIdentity = false) }) { release.await() }
        }
        runCurrent()
        assertEquals(0, active, "every generation change must cancel and join the old collector")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L), starts, "no stale retry or transient-identity startup during mutation")
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L, 1L), starts, "both callers must capture the resulting generation")
        assertEquals(1, peak)
        mutation.join()
        job.cancelAndJoin()
        assertEquals(0, active)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(listOf(0L, 1L), starts, "no retry remains after foreground stop")
    }
}

/** These fixtures exercise realtime only; any unexpected REST request fails immediately. */
internal object UnusedNotificationsApi : NotificationsApi {
    override suspend fun list(limit: Int, unreadOnly: Boolean, before: String?): ApiResult<NotificationListResponse> = error("unexpected REST")
    override suspend fun sync(since: String?, limit: Int): ApiResult<NotificationSyncResponse> = error("unexpected REST")
    override suspend fun get(id: String): ApiResult<NotificationRow> = error("unexpected REST")
    override suspend fun unreadCount(): ApiResult<UnreadCountResponse> = error("unexpected REST")
    override suspend fun markRead(id: String): ApiResult<Unit> = error("unexpected REST")
    override suspend fun markAllRead(through: String): ApiResult<Unit> = error("unexpected REST")
    override suspend fun getPreferences(): ApiResult<NotificationPreferences> = error("unexpected REST")
    override suspend fun updatePreferences(update: NotificationPreferencesUpdate): ApiResult<NotificationPreferences> = error("unexpected REST")
    override suspend fun capability(): ApiResult<NotificationCapability> = error("unexpected REST")
}
