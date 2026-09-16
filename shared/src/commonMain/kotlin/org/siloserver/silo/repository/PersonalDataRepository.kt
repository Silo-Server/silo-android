package org.siloserver.silo.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.apiv2.HistoryContinuationV2
import org.siloserver.silo.network.apiv2.HistoryPageV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import org.siloserver.silo.repository.port.NoOpCatalogCachePort
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.canServeCache

open class PersonalDataRepository(
    private val personalDataApi: PersonalDataApi,
    /**
     * Local-first side-channel (Track B). Defaults to the no-op port so
     * commonMain/tests stay network-only; the Android platform module binds a
     * Room-backed [UserItemStatePort] that records an optimistic projection +
     * outbox op around each content-level mutation below.
     */
    private val userItemStatePort: UserItemStatePort = NoOpUserItemStatePort,
    /** Offline read cache for the library list (Track B). No-op by default. */
    private val catalogCache: CatalogCachePort = NoOpCatalogCachePort,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    membershipPort: org.siloserver.silo.repository.port.MembershipPort? = null,
) {
    val memberships = MembershipActions(membershipPort, identityTransitions)

    // -- Libraries --

    /** Lists the libraries visible to the current user (offline: last cached list). */
    suspend fun listUserLibraries(): ApiResult<List<UserLibrary>> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val result = personalDataApi.listUserLibraries()
        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration) { cacheWriteLease ->
                catalogCache.cacheLibraries(result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedLibraries()?.let { return ApiResult.Success(it) }
        }
        return result
    }

    // -- Favorites --

    suspend fun isFavorite(itemId: String): ApiResult<Boolean> =
        memberships.read(itemId, org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE)

    suspend fun isInWatchlist(itemId: String): ApiResult<Boolean> =
        memberships.read(itemId, org.siloserver.silo.repository.port.MembershipPort.Kind.WATCHLIST)

    // -- History --

    /** Lists the user's watch history with pagination. */
    suspend fun listHistory(continuation: HistoryContinuationV2? = null, limit: Int = 40): ApiResult<HistoryPageV2> =
        personalDataApi.listHistory(continuation, limit)

    // -- Progress --

    /** Legacy sessionless convenience path: admitted playback uses sequenced v2 progress. */
    open suspend fun syncProgress(items: List<SyncProgressItem>): ApiResult<Unit> =
        ApiResult.Error(0, "playback_unavailable", "Progress needs an admitted v2 playback session. Start playback again.")

    // -- Ratings --

    /** Sets or updates the user's star rating (integer 1-5) for a specific item. */
    suspend fun setRating(itemId: String, rating: Int): ApiResult<Unit> =
        writePersonal(org.siloserver.silo.repository.port.PersonalWrite.Rating(itemId, rating))

    suspend fun deleteRating(itemId: String): ApiResult<Unit> =
        writePersonal(org.siloserver.silo.repository.port.PersonalWrite.Rating(itemId, null))

    open suspend fun setWatched(itemId: String, watched: Boolean): ApiResult<Unit> =
        writePersonal(org.siloserver.silo.repository.port.PersonalWrite.Watched(itemId, watched))

    private val personalDispatchMutex = Mutex()
    private val consumedPersonalIntents = mutableSetOf<Long>()
    private var personalSequence = 0L
    private val latestPersonalIntents = mutableMapOf<Pair<String, String>, org.siloserver.silo.repository.port.PersonalWriteIntent>()

    private fun personalIntentKey(command: org.siloserver.silo.repository.port.PersonalWrite) = command.itemId to
        when (command) {
            is org.siloserver.silo.repository.port.PersonalWrite.Watched -> "watched"
            is org.siloserver.silo.repository.port.PersonalWrite.Rating -> "rating"
        }

    fun beginWatched(itemId: String, watched: Boolean) = beginPersonal(org.siloserver.silo.repository.port.PersonalWrite.Watched(itemId, watched))
    fun beginRating(itemId: String, rating: Int?) = beginPersonal(org.siloserver.silo.repository.port.PersonalWrite.Rating(itemId, rating))
    private fun beginPersonal(command: org.siloserver.silo.repository.port.PersonalWrite) =
        org.siloserver.silo.repository.port.PersonalWriteIntent(command, identityTransitions.generation.value, ++personalSequence)
            .also { latestPersonalIntents[personalIntentKey(command)] = it }
    fun isCurrent(intent: org.siloserver.silo.repository.port.PersonalWriteIntent) =
        intent.identityGeneration == identityTransitions.generation.value && latestPersonalIntents[personalIntentKey(intent.command)] == intent

    private suspend fun writePersonal(command: org.siloserver.silo.repository.port.PersonalWrite): ApiResult<Unit> =
        performPersonalWrite(beginPersonal(command))

    suspend fun performPersonalWrite(intent: org.siloserver.silo.repository.port.PersonalWriteIntent): ApiResult<Unit> {
        if (intent.identityGeneration != identityTransitions.generation.value)
            return identityChanged()
        val admitted = personalDispatchMutex.withLock { consumedPersonalIntents.add(intent.sequence) }
        if (!admitted) return ApiResult.Error(0, "personal_write_consumed", "This action was already submitted. Its outcome will not be replayed.")
        return dispatchPersonalWrite(intent)
    }

    private suspend fun dispatchPersonalWrite(intent: org.siloserver.silo.repository.port.PersonalWriteIntent): ApiResult<Unit> {
        val command = intent.command
        if (!command.valid()) return ApiResult.Error(422, "validation_failed", "Invalid personal-data command.")
        val handle = userItemStatePort.beginPersonalWrite(command)
            ?: return ApiResult.Error(0, "personal_write_pending", "This item needs an active saved account with no unresolved personal-data writes.")
        if (intent.identityGeneration != identityTransitions.generation.value)
            return identityChanged()
        val result = personalDataApi.writePersonal(handle)
        if (intent.identityGeneration != identityTransitions.generation.value)
            return identityChanged()
        if (result is ApiResult.Success) userItemStatePort.completePersonalWrite(handle)
        else userItemStatePort.abandonPersonalWrite(handle)
        return result
    }

    private suspend fun writeIfIdentityUnchanged(
        requestGeneration: Long,
        write: suspend (CatalogCacheWriteLease) -> Unit,
    ) {
        if (requestGeneration == identityTransitions.generation.value) {
            write(CatalogCacheWriteLease(requestGeneration))
        }
    }
}
