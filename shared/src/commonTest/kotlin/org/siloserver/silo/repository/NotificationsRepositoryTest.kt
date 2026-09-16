package org.siloserver.silo.repository

import org.siloserver.silo.model.notifications.NotificationCapability
import org.siloserver.silo.model.notifications.NotificationListResponse
import org.siloserver.silo.model.notifications.NotificationPreferences
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationSyncResponse
import org.siloserver.silo.model.notifications.UnreadCountResponse
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.NotificationRealtimeEvent
import org.siloserver.silo.network.api.NotificationsApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsRepositoryTest {

    private fun row(
        id: String,
        createdAt: String,
        readAt: String? = null,
    ) = NotificationRow(
        id = id,
        rawType = "episode.available",
        profileId = "p",
        reasonFlags = JsonObject(emptyMap()),
        createdAt = createdAt,
        readAt = readAt,
    )

    // ---- pure recomputeUnread -------------------------------------------------

    @Test
    fun `recomputeUnread counts only unread rows`() {
        val rows = listOf(
            row("a", "2026-06-12T09:00:00Z"),
            row("b", "2026-06-12T08:00:00Z", readAt = "2026-06-12T09:30:00Z"),
            row("c", "2026-06-12T07:00:00Z"),
        )
        assertEquals(2, recomputeUnread(rows))
    }

    // ---- pure applyEvent fold -------------------------------------------------

    @Test
    fun `created prepends row and increments unread`() {
        val state = NotificationsState(
            rows = listOf(row("a", "2026-06-12T09:00:00Z")),
            unreadCount = 1,
        )
        val next = applyEvent(
            state,
            NotificationRealtimeEvent.Created(row("b", "2026-06-12T09:05:00Z")),
        )
        assertEquals(listOf("b", "a"), next.rows.map { it.id })
        assertEquals(2, next.unreadCount)
    }

    @Test
    fun `created dedupes an already-present id without double counting`() {
        val state = NotificationsState(
            rows = listOf(row("a", "2026-06-12T09:00:00Z")),
            unreadCount = 1,
        )
        val next = applyEvent(
            state,
            NotificationRealtimeEvent.Created(row("a", "2026-06-12T09:00:00Z")),
        )
        assertEquals(listOf("a"), next.rows.map { it.id })
        assertEquals(1, next.unreadCount)
    }

    @Test
    fun `read flips read_at, decrements unread, and is idempotent`() {
        val state = NotificationsState(
            rows = listOf(
                row("a", "2026-06-12T09:00:00Z"),
                row("b", "2026-06-12T08:00:00Z"),
            ),
            unreadCount = 2,
        )
        val once = applyEvent(state, NotificationRealtimeEvent.Read("a"))
        assertTrue(once.rows.first { it.id == "a" }.isRead)
        assertEquals(1, once.unreadCount)

        // Idempotent: reading an already-read row does not go negative.
        val twice = applyEvent(once, NotificationRealtimeEvent.Read("a"))
        assertEquals(1, twice.unreadCount)
    }

    @Test
    fun `read for unknown id is a no-op`() {
        val state = NotificationsState(
            rows = listOf(row("a", "2026-06-12T09:00:00Z")),
            unreadCount = 1,
        )
        val next = applyEvent(state, NotificationRealtimeEvent.Read("zzz"))
        assertEquals(state, next)
    }

    @Test
    fun `readAll marks every row read and zeroes unread`() {
        val state = NotificationsState(
            rows = listOf(
                row("a", "2026-06-12T09:00:00Z"),
                row("b", "2026-06-12T08:00:00Z", readAt = "2026-06-12T09:30:00Z"),
            ),
            unreadCount = 1,
        )
        val next = applyEvent(state, NotificationRealtimeEvent.ReadAll)
        assertTrue(next.rows.all { it.isRead })
        assertEquals(0, next.unreadCount)
    }

    @Test
    fun `snapshot merges by id newest-first and recomputes unread`() {
        val state = NotificationsState(
            rows = listOf(
                row("a", "2026-06-12T09:00:00Z"),
                row("old", "2026-06-12T06:00:00Z", readAt = "2026-06-12T07:00:00Z"),
            ),
            unreadCount = 1,
        )
        // snapshot brings a new unread "b" plus an updated "a" (now read).
        val next = applyEvent(
            state,
            NotificationRealtimeEvent.Snapshot(
                listOf(
                    row("b", "2026-06-12T09:10:00Z"),
                    row("a", "2026-06-12T09:00:00Z", readAt = "2026-06-12T09:20:00Z"),
                ),
            ),
        )
        // dedupe by id, snapshot copy of "a" wins, sorted newest-first.
        assertEquals(listOf("b", "a", "old"), next.rows.map { it.id })
        assertEquals(1, next.rows.count { it.id == "a" })
        assertTrue(next.rows.first { it.id == "a" }.isRead) // snapshot copy (read) won
        assertEquals(1, next.unreadCount) // only "b" unread
    }

    @Test
    fun `closed event leaves state unchanged`() {
        val state = NotificationsState(
            rows = listOf(row("a", "2026-06-12T09:00:00Z")),
            unreadCount = 1,
        )
        assertEquals(state, applyEvent(state, NotificationRealtimeEvent.Closed("x")))
    }

    // ---- repository over a fake API ------------------------------------------

    private class FakeNotificationsApi : NotificationsApi {
        var listResponse: ApiResult<NotificationListResponse> =
            ApiResult.Success(NotificationListResponse())
        var unreadResponse: ApiResult<UnreadCountResponse> =
            ApiResult.Success(UnreadCountResponse(0))
        var markReadResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var markAllReadResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var prefsResponse: ApiResult<NotificationPreferences> =
            ApiResult.Success(NotificationPreferences())
        var capabilityResponse: ApiResult<NotificationCapability> =
            ApiResult.Success(NotificationCapability())
        var markReadCalls = mutableListOf<String>()
        var markAllReadCalls = 0
        var listCalls = 0

        override suspend fun list(limit: Int, unreadOnly: Boolean, before: String?) = listResponse.also { listCalls++ }
        override suspend fun sync(since: String?, limit: Int) =
            ApiResult.Success(NotificationSyncResponse())
        override suspend fun get(id: String) = ApiResult.Success(
            NotificationRow(id, "episode.available", "p", createdAt = ""),
        )
        override suspend fun unreadCount() = unreadResponse
        override suspend fun markRead(id: String): ApiResult<Unit> {
            markReadCalls += id
            return markReadResult
        }
        override suspend fun markAllRead(through: String): ApiResult<Unit> {
            markAllReadCalls++
            return markAllReadResult
        }
        override suspend fun getPreferences() = prefsResponse
        override suspend fun updatePreferences(update: NotificationPreferencesUpdate) = prefsResponse
        override suspend fun capability() = capabilityResponse
    }

    @Test
    fun `refresh loads unread count and first page`() = kotlinx.coroutines.test.runTest {
        val api = FakeNotificationsApi().apply {
            unreadResponse = ApiResult.Success(UnreadCountResponse(2))
            listResponse = ApiResult.Success(
                NotificationListResponse(
                    notifications = listOf(
                        row("a", "2026-06-12T09:00:00Z"),
                        row("b", "2026-06-12T08:00:00Z", readAt = "2026-06-12T09:30:00Z"),
                    ),
                    nextCursor = "cur-1",
                ),
            )
        }
        val repo = NotificationsRepository(api)
        repo.refresh()
        assertEquals(2, repo.unreadCount.value)
        assertEquals(listOf("a", "b"), repo.rows.value.map { it.id })
        assertEquals("cur-1", repo.nextCursor.value)
    }

    @Test
    fun `loadMore appends the next page and dedupes`() = kotlinx.coroutines.test.runTest {
        val api = FakeNotificationsApi().apply {
            listResponse = ApiResult.Success(
                NotificationListResponse(
                    notifications = listOf(row("a", "2026-06-12T09:00:00Z")),
                    nextCursor = "cur-1",
                ),
            )
        }
        val repo = NotificationsRepository(api)
        repo.refresh()
        api.listResponse = ApiResult.Success(
            NotificationListResponse(
                notifications = listOf(
                    row("a", "2026-06-12T09:00:00Z"), // duplicate of page 1
                    row("z", "2026-06-12T07:00:00Z"),
                ),
                nextCursor = null,
            ),
        )
        repo.loadMore("cur-1")
        assertEquals(listOf("a", "z"), repo.rows.value.map { it.id })
        assertEquals(null, repo.nextCursor.value)
    }

    @Test
    fun `markRead is optimistic and reverts on failure`() = kotlinx.coroutines.test.runTest {
        val api = FakeNotificationsApi().apply {
            listResponse = ApiResult.Success(
                NotificationListResponse(notifications = listOf(row("a", "2026-06-12T09:00:00Z"))),
            )
            unreadResponse = ApiResult.Success(UnreadCountResponse(1))
        }
        val repo = NotificationsRepository(api)
        repo.refresh()
        assertEquals(1, repo.unreadCount.value)

        api.markReadResult = ApiResult.Error(500, "internal_error", "boom")
        repo.markRead("a")
        // reverted
        assertEquals(1, repo.unreadCount.value)
        assertFalse(repo.rows.value.first { it.id == "a" }.isRead)
        assertEquals(listOf("a"), api.markReadCalls)
    }

    @Test
    fun `connectRealtime folds a created event without rereading the inbox`() = kotlinx.coroutines.test.runTest {
        val api = FakeNotificationsApi()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<NotificationRealtimeEvent>(
            replay = 0, extraBufferCapacity = 8,
        )
        val realtime = object : org.siloserver.silo.network.NotificationsRealtimeClient {
            override fun connect(): kotlinx.coroutines.flow.Flow<NotificationRealtimeEvent> = events
        }
        val repo = NotificationsRepository(api, realtimeFactory = { realtime })
        val job = repo.connectRealtime(this)
        // Let the launched collector subscribe before we emit (replay=0 drops
        // emissions made before a subscriber is present).
        events.subscriptionCount.first { it > 0 }
        events.emit(NotificationRealtimeEvent.Created(row("live", "2026-06-12T10:00:00Z")))
        kotlinx.coroutines.yield()
        assertEquals(listOf("live"), repo.rows.value.map { it.id })
        assertEquals(1, repo.unreadCount.value)
        assertEquals(0, api.listCalls)
        events.emit(NotificationRealtimeEvent.Invalidate)
        kotlinx.coroutines.yield()
        assertEquals(1, api.listCalls, "a signed read cutoff cannot fold locally and must reread the inbox")
        job.cancel()
    }

    @Test
    fun `reset clears rows unread and cursor on profile switch`() = kotlinx.coroutines.test.runTest {
        val api = FakeNotificationsApi().apply {
            listResponse = ApiResult.Success(
                NotificationListResponse(
                    notifications = listOf(row("a", "2026-06-12T09:00:00Z")),
                    nextCursor = "cur-1",
                ),
            )
            unreadResponse = ApiResult.Success(UnreadCountResponse(1))
        }
        val repo = NotificationsRepository(api)
        repo.refresh()
        repo.reset()
        assertTrue(repo.rows.value.isEmpty())
        assertEquals(0, repo.unreadCount.value)
        assertEquals(null, repo.nextCursor.value)
    }

    @Test
    fun `reset emits on resetSignals so foreground starter can reconnect`() = kotlinx.coroutines.test.runTest {
        val repo = NotificationsRepository(FakeNotificationsApi())
        val signals = mutableListOf<Unit>()
        val collector = launch { repo.resetSignals.collect { signals += it } }
        // Let the collector subscribe before emitting (replay=0 drops emissions
        // made before a subscriber is present).
        kotlinx.coroutines.yield()

        repo.reset()
        kotlinx.coroutines.yield()
        assertEquals(1, signals.size)

        repo.reset()
        kotlinx.coroutines.yield()
        assertEquals(2, signals.size)

        collector.cancel()
    }

    @Test
    fun `reset is synchronous even with no subscriber`() {
        // tryEmit into a buffered DROP_OLDEST flow never suspends or throws,
        // so reset() stays callable from the synchronous selectProfile path.
        val repo = NotificationsRepository(FakeNotificationsApi())
        repo.reset()
        assertTrue(repo.rows.value.isEmpty())
    }

    // ---- blank createdAt sentinel (Part 1) -----------------------------------

    @Test
    fun `read event on blank-createdAt row marks it read and decrements unread`() {
        // A row whose createdAt is blank previously could never become isRead
        // because isRead = !readAt.isNullOrBlank() and the old code set
        // readAt = createdAt (i.e. blank -> blank -> still isNullOrBlank).
        // After the fix the READ_SENTINEL "1970-01-01T00:00:00Z" is used instead.
        val state = NotificationsState(
            rows = listOf(
                row("a", ""),        // blank createdAt
                row("b", "2026-06-12T09:00:00Z"),
            ),
            unreadCount = 2,
        )
        val next = applyEvent(state, NotificationRealtimeEvent.Read("a"))
        assertTrue(next.rows.first { it.id == "a" }.isRead,
            "Row with blank createdAt must become isRead after a Read event")
        assertEquals(1, next.unreadCount,
            "unreadCount must decrement when the blank-createdAt row is marked read")
    }

    @Test
    fun `readAll on blank-createdAt rows marks all read and zeroes unread`() {
        val state = NotificationsState(
            rows = listOf(
                row("a", ""),
                row("b", ""),
            ),
            unreadCount = 2,
        )
        val next = applyEvent(state, NotificationRealtimeEvent.ReadAll)
        assertTrue(next.rows.all { it.isRead },
            "All blank-createdAt rows must be marked read after ReadAll")
        assertEquals(0, next.unreadCount)
    }

    // ---- isAuthClose (Part 2 pure helper) ------------------------------------

    @Test
    fun `isAuthClose returns true for 401 reason`() {
        assertTrue(NotificationRealtimeEvent.Closed("401 Unauthorized").isAuthClose())
    }

    @Test
    fun `isAuthClose returns true for 403 reason`() {
        assertTrue(NotificationRealtimeEvent.Closed("403 Forbidden").isAuthClose())
    }

    @Test
    fun `isAuthClose returns true for auth-keyword reason`() {
        assertTrue(NotificationRealtimeEvent.Closed("auth_failure").isAuthClose())
    }

    @Test
    fun `isAuthClose returns false for non-auth Closed`() {
        assertFalse(NotificationRealtimeEvent.Closed("network_error").isAuthClose())
    }

    @Test
    fun `isAuthClose returns false for null reason`() {
        assertFalse(NotificationRealtimeEvent.Closed(null).isAuthClose())
    }

    @Test
    fun `isAuthClose returns false for non-Closed events`() {
        assertFalse(NotificationRealtimeEvent.ReadAll.isAuthClose())
        assertFalse(NotificationRealtimeEvent.Read("x").isAuthClose())
    }

    // ---- connectRealtime: auth close stops loop and sets realtimeFatal -------

    @Test
    fun `connectRealtime stops reconnecting and sets realtimeFatal on auth Closed`() =
        kotlinx.coroutines.test.runTest {
            val api = FakeNotificationsApi()
            var connectCount = 0
            // Each call to connect() returns a flow that immediately emits a
            // Closed event with an auth reason, then completes.
            val realtime = object : org.siloserver.silo.network.NotificationsRealtimeClient {
                override fun connect(): kotlinx.coroutines.flow.Flow<NotificationRealtimeEvent> {
                    connectCount++
                    return kotlinx.coroutines.flow.flowOf(
                        NotificationRealtimeEvent.Closed("401 Unauthorized"),
                    )
                }
            }
            val repo = NotificationsRepository(api, realtimeFactory = { realtime })
            val job = repo.connectRealtime(this)
            // Give the loop a chance to run. Because reconnect uses delay, and
            // testScheduler advances time, we advance past the initial backoff.
            testScheduler.advanceTimeBy(2_000L)
            kotlinx.coroutines.yield()
            job.cancel()

            // connect() must have been called exactly once — the loop must not
            // have retried after an auth-class Closed.
            assertEquals(1, connectCount,
                "Auth-close must stop the reconnect loop (connectCount=$connectCount)")
            assertTrue(repo.realtimeFatal.value,
                "realtimeFatal must be true after an auth-class Closed")
        }

    // ---- connectRealtime: backoff resets on first event of each connect ------

    @Test
    fun `connectRealtime resets backoff when first event of a connection arrives`() =
        kotlinx.coroutines.test.runTest {
            val api = FakeNotificationsApi()
            // Simulate a pattern: first connect yields a Created event then a
            // clean Closed; the second connect also closes cleanly; the third
            // connect we cancel. The backoff must NOT have escalated after the
            // first healthy-then-closed cycle.
            var connectCount = 0
            // We use a channel to control what each connection returns.
            val realtime = object : org.siloserver.silo.network.NotificationsRealtimeClient {
                override fun connect(): kotlinx.coroutines.flow.Flow<NotificationRealtimeEvent> {
                    connectCount++
                    return when (connectCount) {
                        1 -> kotlinx.coroutines.flow.flowOf(
                            NotificationRealtimeEvent.Created(row("live", "2026-06-12T10:00:00Z")),
                            NotificationRealtimeEvent.Closed(), // clean close after healthy traffic
                        )
                        else -> kotlinx.coroutines.flow.flow {
                            // Suspend indefinitely so the test can cancel.
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                }
            }
            val repo = NotificationsRepository(api, realtimeFactory = { realtime })
            val job = repo.connectRealtime(this)
            // Advance past the initial backoff (1 s) — because backoff should have
            // been RESET by the healthy Created event, the second connect fires
            // after 1 s (not 2 s).
            testScheduler.advanceTimeBy(1_100L)
            kotlinx.coroutines.yield()

            assertTrue(connectCount >= 2,
                "Second connect must have fired after reset-to-initial backoff (connectCount=$connectCount)")
            assertFalse(repo.realtimeFatal.value,
                "realtimeFatal must remain false after a clean close")
            job.cancel()
        }

    // ---- atomic mutate consistency -------------------------------------------

    /**
     * Verifies that after applying an event via the production [mutate] path,
     * [rows], [unreadCount], and the internal [_state] all agree. This would
     * fail if the read-modify-write were non-atomic (e.g. `_rows` updated but
     * `_unreadCount` still reflecting the old state, or vice-versa).
     *
     * We seed two unread rows, then apply a [NotificationRealtimeEvent.Read]
     * event for one of them. After the fold:
     *   - rows.value must contain exactly the two rows
     *   - unreadCount.value must equal 1 (one row remaining unread)
     *   - unreadCount.value must equal recomputeUnread(rows.value) — the
     *     derived flows must be consistent with each other and with _state.
     */
    @Test
    fun `mutate keeps rows and unreadCount consistent with state after event`() {
        val repo = NotificationsRepository(FakeNotificationsApi())

        // Seed two unread rows via the internal seam.
        repo.seedForTest(
            NotificationsState(
                rows = listOf(
                    row("x", "2026-06-12T10:00:00Z"),
                    row("y", "2026-06-12T09:00:00Z"),
                ),
                unreadCount = 2,
            ),
        )

        // Apply a Read event for row "x" — this goes through mutate.
        repo.applyForTest(NotificationRealtimeEvent.Read("x"))

        val currentRows = repo.rows.value
        val currentUnread = repo.unreadCount.value

        // Row "x" must now be marked read.
        assertTrue(currentRows.first { it.id == "x" }.isRead)
        // Row "y" must still be unread.
        assertFalse(currentRows.first { it.id == "y" }.isRead)

        // The derived unreadCount must equal 1.
        assertEquals(1, currentUnread)

        // Critical: unreadCount must be consistent with the rows in the flow —
        // this is the invariant that non-atomic read-modify-write could break.
        assertEquals(recomputeUnread(currentRows), currentUnread)
    }
}
