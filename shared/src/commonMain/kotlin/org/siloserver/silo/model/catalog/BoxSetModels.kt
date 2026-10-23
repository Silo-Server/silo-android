package org.siloserver.silo.model.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A movie box set: the movies TMDB lists in one collection (a franchise such
 * as Harry Potter) that the viewer can see in one library. A library with
 * box sets on browses with `group=box_set`, and the server answers a card of
 * type [BOX_SET_TYPE] carrying this in place of the collection's movies.
 */
@Serializable
data class CatalogBoxSet(
    /** The TMDB collection id; pass it with [libraryId] to open the box set. */
    val id: String,
    @SerialName("library_id") val libraryId: String,
    val title: String,
    val overview: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    /** The collection's movies in the library that the viewer can see. */
    @SerialName("movie_count") val movieCount: Int = 0,
    /** How many of those movies the profile has played. */
    @SerialName("played_count") val playedCount: Int = 0,
    @SerialName("first_year") val firstYear: Int? = null,
    @SerialName("last_year") val lastYear: Int? = null,
)

/** A box set with its movies the viewer can see, in collection (release) order. */
data class CatalogBoxSetDetail(
    val boxSet: CatalogBoxSet,
    val items: List<BrowseItem>,
)

/** `type` of a box set card in a `group=box_set` browse. */
const val BOX_SET_TYPE = "box_set"

/** The catalog `group` that collapses a collection's movies into one box set card. */
const val BOX_SET_GROUP = "box_set"

/** The box set this card stands for, or null for any other card. */
val BrowseItem.boxSetCard: CatalogBoxSet?
    get() = boxSet?.takeIf { type == BOX_SET_TYPE }

/** "8 films", or "1 film". */
fun boxSetFilmCount(count: Int): String = if (count == 1) "1 film" else "$count films"

/** "2001 – 2011", one year when the box set spans one, or null with no year. */
fun CatalogBoxSet.yearsLabel(): String? {
    val first = firstYear?.takeIf { it > 0 }
    val last = lastYear?.takeIf { it > 0 }
    return when {
        first != null && last != null && first != last -> "$first – $last"
        else -> (first ?: last)?.toString()
    }
}

/** A card's caption: watched progress once started, else the years or the film count. */
fun CatalogBoxSet.cardCaption(): String =
    if (playedCount > 0) "$playedCount / $movieCount watched" else yearsLabel() ?: boxSetFilmCount(movieCount)

/** The box set page's facts: film count, years, and watched progress once started. */
fun CatalogBoxSet.heroFacts(): List<String> = listOfNotNull(
    boxSetFilmCount(movieCount),
    yearsLabel(),
    "$playedCount of $movieCount watched".takeIf { playedCount > 0 },
)

/** Share of the box set's movies the profile has played, or null before the first. */
val CatalogBoxSet.watchedFraction: Float?
    get() = if (playedCount > 0 && movieCount > 0) (playedCount.toFloat() / movieCount).coerceAtMost(1f) else null

/** Some of the box set's movies are played, but not all. */
val CatalogBoxSet.isStarted: Boolean
    get() = playedCount in 1 until movieCount

/**
 * The movie a box set's play button starts: the first one in collection order
 * the profile hasn't played (an in-progress movie is not played yet), or the
 * first one once all are played. Null for an empty box set.
 */
fun boxSetNextMovie(items: List<BrowseItem>): BrowseItem? =
    items.firstOrNull { it.userState?.played != true } ?: items.firstOrNull()

/** Where [boxSetNextMovie] resumes, or null to start it from the beginning. */
fun BrowseItem.boxSetResumePosition(): Double? =
    positionSeconds?.takeIf { it > 0 && userState?.played != true }

/** The play button's label for [next], the movie [boxSetNextMovie] picked. */
fun CatalogBoxSet.playLabel(next: BrowseItem): String =
    if (isStarted || next.boxSetResumePosition() != null) "Continue · ${next.title}" else "Play ${next.title}"

/**
 * A search result's box set as a grid card. Search can return one collection
 * from two libraries, so the card's content id names the library too.
 */
fun CatalogBoxSet.toSearchCard(): BrowseItem = BrowseItem(
    contentId = "$BOX_SET_TYPE:$libraryId:$id",
    type = BOX_SET_TYPE,
    title = title,
    overview = overview,
    posterUrl = posterUrl,
    posterThumbhash = posterThumbhash,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
    boxSet = this,
)
