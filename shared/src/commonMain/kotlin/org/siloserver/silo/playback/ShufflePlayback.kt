package org.siloserver.silo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.model.shuffle.Shuffle
import org.siloserver.silo.model.shuffle.ShuffleScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ShufflesV2Api

/** A shuffle's next pick in the shape the post-roll renders. */
data class ShuffleNextPick(
    val contentId: String,
    /** False for a movie, which has no series, season, or episode line. */
    val isEpisode: Boolean,
    val title: String,
    val seriesTitle: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val stillUrl: String?,
    val stillThumbhash: String?,
    val overview: String?,
    val runtimeMinutes: Int,
    /** An episode the profile hasn't started, by the pick's own watch state; never a movie. */
    val isUnwatched: Boolean,
)

/** Names what a shuffle draws from: "Movies", or "Breaking Bad · Season 2" for a season. */
fun ShuffleScope.label(): String =
    parentTitle?.takeIf { it.isNotBlank() }?.let { "$it · $title" } ?: title

/**
 * The pick that plays after [playingContentId], or null when the shuffle is
 * finished: a scope with one playable item announces that item again, and
 * playing it would only restart what just played.
 */
fun Shuffle.nextPickAfter(playingContentId: String): ShuffleNextPick? =
    next.takeIf { it.contentId != playingContentId }?.toShuffleNextPick()

internal fun SectionItem.toShuffleNextPick(): ShuffleNextPick {
    val episode = type == "episode"
    return ShuffleNextPick(
        contentId = contentId,
        isEpisode = episode,
        title = title,
        seriesTitle = seriesTitle.takeIf { episode },
        seasonNumber = if (episode) seasonNumber ?: 0 else 0,
        episodeNumber = if (episode) episodeNumber ?: 0 else 0,
        // An episode card's poster is its 16:9 still; a movie's poster is
        // portrait, so the post-roll shows its backdrop instead.
        stillUrl = (if (episode) posterUrl else null) ?: backdropUrl,
        stillThumbhash = (if (episode) posterThumbhash else null) ?: backdropThumbhash,
        overview = overview,
        runtimeMinutes = runtime ?: 0,
        isUnwatched = episode && EpisodeSpoilers.isUnwatched(this),
    )
}

/**
 * The client half of one running shuffle, owned by a player. The server picks
 * every item; this keeps the last shuffle it returned so the post-roll can
 * announce `next`, and remembers when the server said nothing in the scope can
 * play any more.
 *
 * A failed read keeps the last pick: advancing re-checks it on the server,
 * which replaces a pick that can no longer play.
 */
class ShufflePlayback(
    private val api: ShufflesV2Api,
    val shuffleId: String,
    initial: Shuffle? = null,
) {
    /** The last shuffle the server returned. */
    var latest: Shuffle? = initial
        private set

    /** The server answered `409`: nothing in the scope can play any more. */
    var exhausted: Boolean = false
        private set

    /** The scope label once the shuffle has been read, else null. */
    val scopeLabel: String? get() = latest?.scope?.label()

    /** The pick after [playingContentId]; null when finished or not yet read. */
    fun nextPickAfter(playingContentId: String): ShuffleNextPick? =
        if (exhausted) null else latest?.nextPickAfter(playingContentId)

    // Counts advance and skip calls, and how many are still waiting for the
    // server. A read that overlaps a mutation may answer with the state that
    // mutation replaced, so its answer is dropped.
    private var mutations = 0
    private var mutationsInFlight = 0

    /**
     * Re-reads the shuffle; the server may replace a `next` that can no longer
     * play. The answer is ignored when an advance or skip was in flight when
     * the read started or started while it ran.
     */
    suspend fun refresh(): ApiResult<Shuffle> {
        val startedAfter = mutations
        val overlapped = mutationsInFlight > 0
        val result = api.get(shuffleId)
        return if (!overlapped && mutations == startedAfter) record(result) else result
    }

    /**
     * Moves the shuffle past [fromContentId], the item that finished. Play the
     * returned `current`. A retry for the same item changes nothing.
     */
    suspend fun advance(fromContentId: String): ApiResult<Shuffle> =
        mutate { api.advance(shuffleId, fromContentId) }

    /** Pick Another: replaces the announced next item with another pick. */
    suspend fun pickAnother(): ApiResult<Shuffle> {
        val next = latest?.next ?: return ApiResult.Error(0, "not_loaded", "The shuffle has not loaded yet.")
        return mutate { api.skip(shuffleId, next.contentId) }
    }

    /** Stop shuffling. */
    suspend fun stop(): ApiResult<Unit> = api.delete(shuffleId)

    /**
     * Stop shuffling without waiting for the answer. The player leaves right
     * away, so the request must outlive it; a failure only leaves an unused
     * shuffle the server deletes later.
     */
    fun stopInBackground() {
        detachedScope.launch { stop() }
    }

    private suspend fun mutate(call: suspend () -> ApiResult<Shuffle>): ApiResult<Shuffle> {
        mutations++
        mutationsInFlight++
        try {
            return record(call())
        } finally {
            mutationsInFlight--
        }
    }

    private fun record(result: ApiResult<Shuffle>): ApiResult<Shuffle> {
        when (result) {
            is ApiResult.Success -> {
                latest = result.data
                exhausted = false
            }
            is ApiResult.Error -> if (result.code == 409) exhausted = true
            is ApiResult.NetworkError -> Unit
        }
        return result
    }
}

private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
