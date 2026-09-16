package org.siloserver.silo.repository

import org.siloserver.silo.model.notifications.NotificationCapability
import org.siloserver.silo.model.notifications.NotificationPreferences
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.network.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.NotificationRealtimeEvent
import org.siloserver.silo.network.NotificationsRealtimeClient
import org.siloserver.silo.network.api.NotificationsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Immutable repository state, folded by the pure [applyEvent]. Newest-first
 * row ordering matches the inbox list API.
 */
data class NotificationsState(
    val rows: List<NotificationRow> = emptyList(),
    val unreadCount: Int = 0,
)

/** Count of unread rows. Pure. */
fun recomputeUnread(rows: List<NotificationRow>): Int = rows.count { !it.isRead }

/** Newest-first by created_at (string ISO-8601 sorts lexicographically). */
private fun List<NotificationRow>.sortedNewestFirst(): List<NotificationRow> =
    sortedByDescending { it.createdAt }

/** Dedupe by id keeping the FIRST occurrence (callers put the winner first). */
private fun List<NotificationRow>.dedupeById(): List<NotificationRow> {
    val seen = HashSet<String>(size)
    return filter { seen.add(it.id) }
}

/**
 * Sentinel written to [org.siloserver.silo.model.notifications.NotificationRow.readAt] when the
 * row's [org.siloserver.silo.model.notifications.NotificationRow.createdAt] is blank.
 * [org.siloserver.silo.model.notifications.NotificationRow.isRead] checks `!readAt.isNullOrBlank()`,
 * so any non-blank value satisfies the predicate. Without this, rows with a blank createdAt could
 * never be marked read because copying blank into readAt leaves it blank.
 */
private const val READ_SENTINEL = "1970-01-01T00:00:00Z"

/**
 * Pure fold of one realtime event into [state]. The repository applies the
 * result to its StateFlows; keeping this pure makes the fold fully unit
 * testable without coroutines.
 *
 *  - [NotificationRealtimeEvent.Created]: prepend (dedupe by id), recompute unread.
 *  - [NotificationRealtimeEvent.Read]: flip read_at on the row, recompute unread.
 *  - [NotificationRealtimeEvent.ReadAll]: mark every row read, unread = 0.
 *  - [NotificationRealtimeEvent.Snapshot]: merge by id (snapshot copy wins),
 *    sort newest-first, recompute unread.
 *  - [NotificationRealtimeEvent.Closed]: no-op (reconnect is the loop's job).
 */
fun applyEvent(state: NotificationsState, event: NotificationRealtimeEvent): NotificationsState =
    when (event) {
        is NotificationRealtimeEvent.Created -> {
            val merged = (listOf(event.row) + state.rows).dedupeById()
            state.copy(rows = merged, unreadCount = recomputeUnread(merged))
        }
        is NotificationRealtimeEvent.Read -> {
            if (state.rows.none { it.id == event.id }) {
                state
            } else {
                val rows = state.rows.map {
                    if (it.id == event.id && !it.isRead) it.copy(readAt = it.createdAt.ifBlank { READ_SENTINEL }) else it
                }
                state.copy(rows = rows, unreadCount = recomputeUnread(rows))
            }
        }
        NotificationRealtimeEvent.ReadAll -> {
            val rows = state.rows.map { if (it.isRead) it else it.copy(readAt = it.createdAt.ifBlank { READ_SENTINEL }) }
            state.copy(rows = rows, unreadCount = 0)
        }
        is NotificationRealtimeEvent.Snapshot -> {
            // Snapshot copies win on id collision, so they come first.
            val merged = (event.rows + state.rows).dedupeById().sortedNewestFirst()
            state.copy(rows = merged, unreadCount = recomputeUnread(merged))
        }
        NotificationRealtimeEvent.Invalidate -> state
        is NotificationRealtimeEvent.Closed -> state
    }

/**
 * Returns true when this [NotificationRealtimeEvent.Closed] event carries an auth-class
 * reason (HTTP 401, 403, or a reason string containing "auth"). Non-[Closed] events
 * always return false. Marked [internal] so tests in the same module can exercise it
 * directly.
 */
internal fun NotificationRealtimeEvent.isAuthClose(): Boolean =
    this is NotificationRealtimeEvent.Closed &&
        (reason?.contains("401") == true || reason?.contains("403") == true ||
            reason?.contains("auth", ignoreCase = true) == true)

/**
 * Singleton owner of notification inbox state. REST is the source of truth;
 * the realtime client is a foreground accelerator folded into the same flows
 * via [applyEvent]. The repository owns reconnect (capped backoff); the client
 * manages a single connection per [NotificationsRealtimeClient.connect].
 *
 * [realtimeFactory] is injected so tests can supply a fake event flow.
 */
class NotificationsRepository(
    private val api: NotificationsApi,
    private val realtimeFactory: () -> NotificationsRealtimeClient? = { null },
    private val tokens: TokenManager? = null,
    private val authorities: DurableLoginAuthorityProvider? = null,
    private val checkpoints: NotificationSyncStore? = null,
    private val identityTransitions: IdentityTransitionBarrier? = null,
) {
    private var epoch = 0L
    private var readCutoff: String? = null
    private var cutoffViewer: AuthScopeSnapshot? = null
    private val refreshMutex = Mutex()
    private data class Viewer(val epoch: Long, val scope: AuthScopeSnapshot?, val api: NotificationsApi)
    private suspend fun viewer(): Viewer {
        val scope = tokens?.snapshotCurrentScope()
        return Viewer(epoch, scope, if (scope != null) api.forScope(scope) else api)
    }
    private suspend fun current(viewer: Viewer): Boolean = viewer.epoch == epoch &&
        (tokens == null || viewer.scope?.isSameIdentityAs(tokens.snapshotCurrentScope()) == true)
    private suspend fun publishFor(viewer: Viewer, block: () -> Unit) {
        if (!current(viewer)) return
        val barrier = identityTransitions
        if (barrier != null && viewer.scope != null)
            barrier.withCurrentGeneration(viewer.scope.identityGeneration) { if (viewer.epoch == epoch) block() }
        else block()
    }

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _state = MutableStateFlow(NotificationsState())

    private val _unreadCount = MutableStateFlow(0)
    private val _rows = MutableStateFlow<List<NotificationRow>>(emptyList())
    private val _nextCursor = MutableStateFlow<String?>(null)
    private val _preferences = MutableStateFlow<NotificationPreferences?>(null)
    private val _capability = MutableStateFlow<NotificationCapability?>(null)

    /** True once [connectRealtime] gives up permanently due to an auth-class [NotificationRealtimeEvent.Closed]. */
    private val _realtimeFatal = MutableStateFlow(false)

    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()
    val rows: StateFlow<List<NotificationRow>> = _rows.asStateFlow()
    val nextCursor: StateFlow<String?> = _nextCursor.asStateFlow()
    val preferences: StateFlow<NotificationPreferences?> = _preferences.asStateFlow()
    val capability: StateFlow<NotificationCapability?> = _capability.asStateFlow()

    /**
     * Becomes true if [connectRealtime] receives an auth-class [NotificationRealtimeEvent.Closed]
     * (401/403/auth reason). When true the realtime loop has permanently stopped; callers should
     * surface an appropriate error state rather than attempting to reconnect.
     */
    val realtimeFatal: StateFlow<Boolean> = _realtimeFatal.asStateFlow()

    // Buffered + drop-oldest so [reset] can emit synchronously (tryEmit never
    // suspends and never fails). Foreground starters collect this WHILE
    // FOREGROUNDED to tear down + recreate the realtime scope (so the socket
    // reconnects with the new profile's ws-ticket) and run a REST [refresh]
    // for the new profile. replay=0: a fresh subscriber should not re-handle a
    // past switch.
    private val _resetSignals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits [Unit] each time [reset] runs (i.e. on a profile switch). The live
     * realtime socket was opened with the previous profile's ws-ticket, so a
     * foregrounded starter must, on each signal, cancel + recreate its realtime
     * scope (reconnecting [connectRealtime] with the new profile's ticket) and
     * call [refresh]. Socket ownership stays in the starter, so this works for
     * both the mobile and TV foreground starters — each collects independently.
     */
    val resetSignals: SharedFlow<Unit> = _resetSignals.asSharedFlow()

    init {
        identityTransitions?.installGate { transition ->
            if (transition.phase == IdentityTransitionPhase.WILL_CHANGE && transition.affectsCurrentIdentity) reset()
        }
    }

    private fun publish(state: NotificationsState) {
        _state.value = state
        _rows.value = state.rows
        _unreadCount.value = state.unreadCount
    }

    /**
     * Atomically transforms [_state] via [MutableStateFlow.updateAndGet], then
     * propagates the result to the derived flows. All forward folds (realtime
     * events, loadMore, markRead/markAllRead) funnel through here so that
     * concurrent callers cannot interleave a stale read with a subsequent write.
     */
    private fun mutate(transform: (NotificationsState) -> NotificationsState) {
        val next = _state.updateAndGet(transform)
        _rows.value = next.rows
        _unreadCount.value = next.unreadCount
    }

    /** Foreground refresh and reconnect always reread authoritative counts and the displayed cutoff. */
    suspend fun refresh() = refreshMutex.withLock {
        val viewer = viewer()
        if (!current(viewer)) return@withLock
        publishFor(viewer) { _error.value = null }
        val count = viewer.api.unreadCount()
        val page = viewer.api.list(limit = 25, unreadOnly = false, before = null)
        if (page is ApiResult.Success) publishFor(viewer) {
            publish(NotificationsState(rows = page.data.notifications.dedupeById(),
                unreadCount = (count as? ApiResult.Success)?.data?.count ?: _unreadCount.value))
            _nextCursor.value = page.data.nextCursor
            readCutoff = page.data.readCutoff
            cutoffViewer = viewer.scope
        }
        if (page !is ApiResult.Success || count !is ApiResult.Success) publishFor(viewer) {
            _error.value = "Notifications could not be refreshed. Retry when connected."
        }
        syncForward(viewer)
    }

    private suspend fun syncForward(viewer: Viewer) {
        val store = checkpoints ?: return
        val authority = authorities?.snapshotDurableLoginAuthority() ?: return
        if (!authority.scope.isSameIdentityAs(viewer.scope)) return
        val key = SiloJson.encodeToString(listOf(authority.scope.serverId, authority.scope.serverUrl,
            authority.loginId, authority.scope.profileId, "50"))
        try {
            var cursor = store.read(key)
            // Bounded per wake; the saved checkpoint resumes a large backlog on the next wake.
            repeat(20) {
                if (!current(viewer)) return
                val result = viewer.api.sync(cursor, 50)
                if (result !is ApiResult.Success) {
                    publishFor(viewer) { _error.value = "Notification catch-up is incomplete. Retry to continue." }
                    return
                }
                val page = result.data
                val checkpoint = page.syncCursor ?: return
                if (!current(viewer)) return
                publishFor(viewer) {
                    mutate { old -> old.copy(rows = (page.notifications + old.rows).dedupeById().sortedNewestFirst(),
                        unreadCount = page.unreadCount) }
                }
                // Retain the forward checkpoint even for an empty or final page.
                store.write(key, checkpoint)
                if (!page.hasMore) return
                cursor = page.nextCursor ?: return
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { publishFor(viewer) { _error.value = "Notification catch-up could not save its checkpoint. Retry to continue." } }
    }

    suspend fun loadMore(cursor: String) {
        val viewer = viewer()
        if (cursor != _nextCursor.value) return
        val result = viewer.api.list(25, false, cursor)
        if (result !is ApiResult.Success) publishFor(viewer) { _error.value = "Older notifications could not be loaded. Retry to continue." }
        if (result is ApiResult.Success) publishFor(viewer) {
            if (cursor != _nextCursor.value) return@publishFor
            mutate { old -> old.copy(rows = (old.rows + result.data.notifications).dedupeById()) }
            _nextCursor.value = result.data.nextCursor
        }
    }

    suspend fun get(id: String): ApiResult<NotificationRow> {
        val viewer = viewer()
        val result = viewer.api.get(id)
        return if (current(viewer)) result else ApiResult.Error(0, "identity_changed", "The active viewer changed.")
    }

    suspend fun markRead(id: String) {
        val viewer = viewer()
        if (viewer.api.markRead(id) is ApiResult.Success) {
            publishFor(viewer) { mutate { applyEvent(it, NotificationRealtimeEvent.Read(id)) } }
        } else publishFor(viewer) { _error.value = "Notification read status could not be confirmed." }
    }

    suspend fun markAllRead() {
        val viewer = viewer()
        val cutoff = readCutoff // Freeze the displayed boundary before sending this user intent.
        if (cutoff == null || cutoffViewer?.isSameIdentityAs(viewer.scope) != true) {
            publishFor(viewer) { _error.value = "Refresh the inbox before marking it read." }
            return
        }
        val result = viewer.api.markAllRead(cutoff)
        if (result !is ApiResult.Success) publishFor(viewer) { _error.value = "Mark all read could not be confirmed. Refresh before trying again." }
        if (result is ApiResult.Success && current(viewer)) refresh()
    }

    suspend fun loadPreferences() {
        val viewer = viewer()
        val result = viewer.api.getPreferences()
        if (result is ApiResult.Success) publishFor(viewer) { _preferences.value = result.data }
    }

    suspend fun updatePreferences(update: NotificationPreferencesUpdate): ApiResult<NotificationPreferences> {
        val viewer = viewer()
        val result = viewer.api.updatePreferences(update)
        if (!current(viewer)) return ApiResult.Error(0, "identity_changed", "The active viewer changed.")
        if (result is ApiResult.Success) publishFor(viewer) { _preferences.value = result.data }
        return result
    }

    suspend fun loadCapability() {
        val viewer = viewer()
        val result = viewer.api.capability()
        if (result is ApiResult.Success) publishFor(viewer) { _capability.value = result.data }
    }

    /**
     * Clears all state on profile switch (badge is per-profile) and signals
     * [resetSignals] so a foregrounded starter can reconnect the realtime
     * socket with the new profile's ticket and refresh. Stays synchronous —
     * [tryEmit] into the buffered flow never suspends.
     */
    fun reset() {
        epoch++
        _error.value = null
        readCutoff = null
        cutoffViewer = null
        publish(NotificationsState())
        _nextCursor.value = null
        _preferences.value = null
        _capability.value = null
        _resetSignals.tryEmit(Unit)
    }

    /**
     * Collects the realtime client with capped-backoff reconnect, folding each
     * event into the state flows. Returns the [Job] so the lifecycle starter
     * can cancel it when the app backgrounds — the reconnect loop honors scope
     * cancellation. A null factory (no realtime binding) makes this a no-op
     * completed job.
     */
    fun connectRealtime(scope: CoroutineScope): Job = scope.launch {
        val client = realtimeFactory() ?: return@launch
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            var established = false // true once the first non-Closed event of this connection arrives
            var authFailed = false  // set inside collect, checked after to break the loop
            val connectionViewer = viewer()
            try {
                client.connect().collect { event ->
                    if (!current(connectionViewer)) return@collect
                    // Auth-class closes are terminal — stop reconnecting.
                    if (event.isAuthClose()) {
                        _realtimeFatal.value = true
                        authFailed = true
                        return@collect
                    }
                    if (!established && event !is NotificationRealtimeEvent.Closed) {
                        // First healthy event: the connection is up — reset backoff so a
                        // healthy-then-clean-close cycle does not escalate the delay.
                        backoffMs = INITIAL_BACKOFF_MS
                        established = true
                    }
                    // The connection snapshot is the reconnect moment, and a signed read
                    // cutoff from another device cannot be folded locally: both reread the
                    // authoritative counts and displayed cutoff. Every other event folds.
                    if (event is NotificationRealtimeEvent.Snapshot || event is NotificationRealtimeEvent.Invalidate) refresh()
                    else publishFor(connectionViewer) { mutate { applyEvent(it, event) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // fall through to backoff-reconnect
            }
            if (authFailed) return@launch
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    // ---- test seams (internal so tests can exercise mutate paths) ----------------

    /** Seeds state atomically. For tests only. */
    internal fun seedForTest(state: NotificationsState) = mutate { state }

    /** Applies one realtime event via the production mutate path. For tests only. */
    internal fun applyForTest(event: NotificationRealtimeEvent) = mutate { applyEvent(it, event) }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
