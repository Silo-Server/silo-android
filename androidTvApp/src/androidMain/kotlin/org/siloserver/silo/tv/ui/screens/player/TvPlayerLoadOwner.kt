package org.siloserver.silo.tv.ui.screens.player

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal data class TvPlayerLoadOwner(
    val generation: Long,
    val contentId: String,
    val preferredFileId: Int?,
    val preferredQuality: String?,
)

/**
 * Carries a fresh-load owner through the shared playback coordinator without
 * teaching the shared API about the TV ViewModel's ownership implementation.
 *
 * [isCurrent] is deliberately evaluated by PlaybackSessionLifecycle while its
 * own transition mutex is held. Checking it before entering that mutex would
 * leave a window where a newer load or exit could win immediately before the
 * stale session was published.
 */
internal class TvPlayerLoadOwnership internal constructor(
    internal val owner: TvPlayerLoadOwner,
    private val registry: TvPlayerLoadOwnerRegistry,
) : AbstractCoroutineContextElement(TvPlayerLoadOwnership) {
    companion object Key : CoroutineContext.Key<TvPlayerLoadOwnership>

    fun isCurrent(): Boolean = registry.owns(owner)
}

internal suspend fun currentTvPlayerLoadOwnership(): TvPlayerLoadOwnership? =
    currentCoroutineContext()[TvPlayerLoadOwnership]

/**
 * Monotonic owner for TV fresh-load publication.
 *
 * Coroutine cancellation is only an optimization: an older, non-cooperative
 * load can still return, but it cannot publish after a newer begin/invalidate.
 */
internal class TvPlayerLoadOwnerRegistry {
    private var generation = 0L
    private var current: TvPlayerLoadOwner? = null

    @Synchronized
    fun begin(
        contentId: String,
        preferredFileId: Int?,
        preferredQuality: String?,
    ): TvPlayerLoadOwner = TvPlayerLoadOwner(
        generation = ++generation,
        contentId = contentId,
        preferredFileId = preferredFileId,
        preferredQuality = preferredQuality,
    ).also { current = it }

    @Synchronized
    fun owns(owner: TvPlayerLoadOwner): Boolean = current == owner

    suspend fun <T> withOwner(
        owner: TvPlayerLoadOwner,
        block: suspend () -> T,
    ): T = withContext(TvPlayerLoadOwnership(owner, this)) {
        block()
    }

    @Synchronized
    fun runIfOwned(owner: TvPlayerLoadOwner, action: () -> Unit): Boolean {
        if (current != owner) return false
        action()
        return true
    }

    suspend fun publishReadyIfOwned(
        owner: TvPlayerLoadOwner,
        sessionId: String?,
        publish: () -> Unit,
        stopStaleSession: suspend (String) -> Unit,
    ): Boolean {
        if (runIfOwned(owner, publish)) return true
        val staleSessionId = sessionId?.takeIf(String::isNotBlank) ?: return false
        withContext(NonCancellable) {
            stopStaleSession(staleSessionId)
        }
        return false
    }

    @Synchronized
    fun invalidate() {
        generation += 1
        current = null
    }
}

/**
 * Exactly-once cleanup token for a Ready session that the shared starter has
 * allocated and deferred, but the TV load has not jointly confirmed yet.
 *
 * Claiming is synchronous so every post-start suspension is covered. Cleanup
 * consumes the token before invoking the manager/lifecycle rollback and always
 * runs in [NonCancellable]. Confirmation consumes the same token without
 * cleanup once ownership has transferred.
 */
internal class TvUnpublishedLoadSessionOwnership(
    private val rollbackSession: suspend (sessionId: String) -> Unit,
) {
    private var sessionId: String? = null

    @Synchronized
    fun acquire(sessionId: String) {
        require(sessionId.isNotBlank())
        check(this.sessionId == null) {
            "An unpublished TV load session is already owned."
        }
        this.sessionId = sessionId
    }

    suspend fun rollbackIfOwned(sessionId: String) {
        val claimed = claim(sessionId) ?: return
        withContext(NonCancellable) {
            rollbackSession(claimed)
        }
    }

    suspend fun rollbackIfOwned() {
        val claimed = claimAny() ?: return
        withContext(NonCancellable) {
            rollbackSession(claimed)
        }
    }

    @Synchronized
    fun transferConfirmed(sessionId: String): Boolean {
        if (this.sessionId != sessionId) return false
        this.sessionId = null
        return true
    }

    @Synchronized
    private fun claim(expectedSessionId: String): String? {
        if (sessionId != expectedSessionId) return null
        return sessionId.also { sessionId = null }
    }

    @Synchronized
    private fun claimAny(): String? =
        sessionId.also { sessionId = null }
}

internal data class TvUnpublishedLoadUiSnapshot<State, Context>(
    val state: State,
    val context: Context,
)

/**
 * Exact UI-side counterpart to [TvUnpublishedLoadSessionOwnership].
 *
 * A replacement session keeps its predecessor snapshot until joint
 * manager/lifecycle confirmation. Rollback consumes only that session's
 * snapshot, so a stale B cleanup can never overwrite an already-published C.
 */
internal class TvUnpublishedLoadUiOwnership<State, Context> {
    private val predecessors =
        mutableMapOf<String, TvUnpublishedLoadUiSnapshot<State, Context>>()

    @Synchronized
    fun register(
        sessionId: String,
        state: State,
        context: Context,
        predecessorSessionId: String?,
    ) {
        require(sessionId.isNotBlank())
        predecessors[sessionId] = predecessors[predecessorSessionId]
            ?: TvUnpublishedLoadUiSnapshot(state, context)
    }

    @Synchronized
    fun confirm(sessionId: String) {
        predecessors.remove(sessionId)
    }

    @Synchronized
    fun snapshotForRollback(
        sessionId: String,
    ): TvUnpublishedLoadUiSnapshot<State, Context>? = predecessors[sessionId]

    @Synchronized
    fun completeRollback(sessionId: String) {
        predecessors.remove(sessionId)
    }
}
