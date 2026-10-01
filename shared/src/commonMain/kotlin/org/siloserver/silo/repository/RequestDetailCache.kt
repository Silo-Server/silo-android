package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestReason
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestUserState
import org.siloserver.silo.model.request.requestInstantKey
import org.siloserver.silo.network.ApiResult

/**
 * Session memory for request detail pages, so opening a title the app has
 * already seen paints its final layout on the first frame instead of building
 * itself as reads land (the Apple clients' `RequestDetailCache`).
 *
 * It holds, keyed by media type + TMDB id: the title detail from the last read
 * or prefetch; the signed-in user's own request records from every
 * `/requests/mine` read; and admin records from the approval queue. A tap
 * seeds it too: a card carries enough of the title for a provisional page.
 * Every page still refreshes from the server; this only decides the first frame.
 *
 * Main-thread confined, like the view models that read it. Profile-scoped:
 * [RequestsRepository.reset] clears it on sign-out and profile or server switches.
 */
class RequestDetailCache(
    /** Runs the shared prefetch queue; null disables prefetch (tests). */
    private val prefetchScope: CoroutineScope? = null,
) {
    data class Key(val mediaType: String, val tmdbId: Int)

    private val details = LinkedHashMap<Key, RequestMediaDetail>()
    private var ownRecords: Map<Key, MediaRequest> = emptyMap()
    private var moderationRecords: Map<Key, MediaRequest> = emptyMap()
    /** The exact request an admin opened from the approval queue; wins over any other for the title. */
    private val pinnedModeration = mutableMapOf<Key, MediaRequest>()
    private val seeds = mutableMapOf<Key, RequestMediaResult>()
    private val prefetchQueue = ArrayDeque<Pair<Key, suspend (Key) -> ApiResult<RequestMediaDetail>>>()
    private var prefetchJob: Job? = null
    /** Bumped by [clear]: a drain from the previous session stops at its next step. */
    private var generation = 0

    fun detail(key: Key): RequestMediaDetail? = details[key]
    fun ownRecord(key: Key): MediaRequest? = ownRecords[key]
    fun moderationRecord(key: Key): MediaRequest? = moderationRecords[key]
    fun pinnedModerationRecord(key: Key): MediaRequest? = pinnedModeration[key]

    /** Any other way into the title opens an ordinary page. */
    fun unpinModeration(key: Key) {
        pinnedModeration.remove(key)
    }

    /** Drops the pin once the request has been decided on. */
    fun unpinModeration(record: MediaRequest) {
        val key = record.cacheKey()
        if (pinnedModeration[key]?.id == record.id) pinnedModeration.remove(key)
    }

    fun pinModeration(record: MediaRequest) {
        pinnedModeration[record.cacheKey()] = record
    }

    /** A page to show before the detail read answers: the cached detail, or one built from a card or record. */
    fun firstFrameDetail(key: Key): RequestMediaDetail? =
        details[key]
            ?: seeds[key]?.let(::provisionalDetail)
            ?: (ownRecords[key] ?: moderationRecords[key])?.let(::provisionalDetail)

    fun store(detail: RequestMediaDetail) {
        val key = Key(detail.mediaType, detail.tmdbId)
        details.remove(key)
        details[key] = detail
        if (details.size > DetailLimit) details.remove(details.keys.first())
    }

    /** A complete `/requests/mine` read. Replaces what was known, so a cancelled request no longer seeds a page. */
    fun storeOwnRecords(records: List<MediaRequest>) {
        ownRecords = records.groupBy { it.cacheKey() }.mapNotNull { (key, list) -> currentRecord(list)?.let { key to it } }.toMap()
    }

    fun storeOwnRecord(record: MediaRequest) {
        val key = record.cacheKey()
        ownRecords = if (record.outcome == RequestOutcome.Cancelled) ownRecords - key else ownRecords + (key to record)
    }

    /**
     * Records in priority order: the first per title wins, so a pending request
     * listed before a failed one is the one the page decides on.
     */
    fun storeModerationRecords(records: List<MediaRequest>) {
        val next = LinkedHashMap<Key, MediaRequest>()
        records.forEach { next.getOrPut(it.cacheKey()) { it } }
        moderationRecords = next
        // A complete read no longer listing a pinned request means someone
        // already decided on it; its pin must not bring the buttons back.
        val queued = records.mapTo(HashSet()) { it.id }
        pinnedModeration.entries.removeAll { it.value.id !in queued }
    }

    fun seed(result: RequestMediaResult) {
        seeds[Key(result.mediaType, result.tmdbId)] = result
    }

    fun clear() {
        generation++
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchQueue.clear()
        details.clear()
        ownRecords = emptyMap()
        moderationRecords = emptyMap()
        pinnedModeration.clear()
        seeds.clear()
    }

    /**
     * Warms the detail for the first rows of a list, one read at a time and only
     * for titles not already cached, so opening any of them lands on the
     * finished page. Lists that load together share one queue.
     */
    fun prefetch(records: List<MediaRequest>, load: suspend (Key) -> ApiResult<RequestMediaDetail>) {
        val scope = prefetchScope ?: return
        val batch = records.asSequence()
            .map { it.cacheKey() }
            .filter { details[it] == null && (it.mediaType == RequestMediaType.Movie || it.mediaType == RequestMediaType.Series) }
            .distinct()
            .take(PrefetchCount)
            .filter { key -> prefetchQueue.none { it.first == key } }
            .toList()
        if (batch.isEmpty()) return
        batch.forEach { prefetchQueue.addLast(it to load) }
        if (prefetchJob != null) return
        val session = generation
        prefetchJob = scope.launch {
            while (session == generation && prefetchQueue.isNotEmpty()) {
                val (key, loader) = prefetchQueue.removeFirst()
                if (details[key] != null) continue
                val result = loader(key)
                if (session != generation) return@launch
                if (result is ApiResult.Success) store(result.data)
            }
            if (session == generation) prefetchJob = null
        }
    }

    companion object {
        /** Bounded so a long browsing session can't grow without limit. */
        private const val DetailLimit = 80
        /** Rows warmed per list read: roughly the first screenful and a bit. */
        const val PrefetchCount = 10

        /**
         * The one of a title's requests that speaks for it: the newest active
         * one, otherwise the newest overall. Null when that newest one was
         * cancelled, so an older decline or failure doesn't come back.
         */
        fun currentRecord(records: List<MediaRequest>): MediaRequest? {
            records.filter { it.outcome == RequestOutcome.Active }
                .maxByOrNull { it.createdAt.requestInstantKey() }
                ?.let { return it }
            return records.maxByOrNull { it.createdAt.requestInstantKey() }
                ?.takeIf { it.outcome != RequestOutcome.Cancelled }
        }

        fun provisionalDetail(result: RequestMediaResult): RequestMediaDetail = RequestMediaDetail(
            mediaType = result.mediaType,
            tmdbId = result.tmdbId,
            title = result.title,
            overview = result.overview,
            posterPath = result.posterPath,
            backdropPath = result.backdropPath,
            releaseDate = result.releaseDate,
            year = result.year,
            voteAverage = result.voteAverage,
            availability = result.availability,
            libraryContentId = result.libraryContentId,
            request = result.request,
        )

        fun provisionalDetail(record: MediaRequest): RequestMediaDetail = RequestMediaDetail(
            mediaType = record.mediaType,
            tmdbId = record.tmdbId,
            title = record.title,
            overview = record.overview,
            posterPath = record.posterPath,
            backdropPath = record.backdropPath,
            year = record.year,
            availability = if (record.state == RequestUserState.Available) RequestAvailability.Available else RequestAvailability.Missing,
            libraryContentId = record.libraryContentId,
            request = RequestState(
                status = record.status,
                state = record.state,
                requestable = false,
                reason = RequestReason.AlreadyRequested,
                requestId = record.id,
            ),
        )
    }
}

fun MediaRequest.cacheKey(): RequestDetailCache.Key = RequestDetailCache.Key(mediaType, tmdbId)
