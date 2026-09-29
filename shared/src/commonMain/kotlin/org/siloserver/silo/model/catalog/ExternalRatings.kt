package org.siloserver.silo.model.catalog

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One external rating as a title page shows it: the source key (`imdb`,
 * `tmdb`, `rt_critic`, or one a plugin adds later), the plain-text mark
 * (`IMDb`, `RT Audience`), a 0-100 score, and the value already formatted on
 * the source's own scale (`8.5`, `93%`). Show [display] as it comes.
 */
@Serializable
data class DisplayRating(
    val source: String,
    val name: String,
    val score: Double,
    val display: String,
)

/**
 * The external ratings a title page or hero shows, shared by the phone and TV
 * apps so both read the same list.
 */
object ExternalRatings {
    const val SOURCE_IMDB = "imdb"
    const val SOURCE_TMDB = "tmdb"

    /**
     * The most ratings a phone-width title page shows. Web and iOS use the
     * same limit. Wide layouts (tablet, fold, TV) show the whole list.
     */
    const val PHONE_LIMIT = 3

    /**
     * The ratings row for a title page. A current server sends [ratings]
     * (empty when there is nothing to show), already chosen and formatted:
     * it is returned untouched. An older server omits the member (`null`), so
     * the row falls back to IMDb then TMDB from the raw out-of-ten scores.
     * Never Rotten Tomatoes: item detail carries raw RT values for metadata
     * editors even when an administrator has not chosen to show them.
     */
    fun forTitle(
        ratings: List<DisplayRating>?,
        ratingImdb: Double?,
        ratingTmdb: Double?,
    ): List<DisplayRating> = ratings ?: listOfNotNull(imdb(ratingImdb), tmdb(ratingTmdb))

    /**
     * The ratings a phone-width title page shows: the first [PHONE_LIMIT]
     * of [ratings], in the server's order.
     */
    fun forPhone(ratings: List<DisplayRating>): List<DisplayRating> = ratings.take(PHONE_LIMIT)

    /** The one rating a hero shows from card fields: IMDb, else TMDB. */
    fun primary(ratingImdb: Double?, ratingTmdb: Double?): DisplayRating? =
        imdb(ratingImdb) ?: tmdb(ratingTmdb)

    fun imdb(value: Double?): DisplayRating? = outOfTen(SOURCE_IMDB, "IMDb", value)

    fun tmdb(value: Double?): DisplayRating? = outOfTen(SOURCE_TMDB, "TMDB", value)

    /**
     * One decimal with a `.` separator whatever the device locale (`8.5`,
     * never `8,5`), rounding half up like the web client.
     */
    fun formatOneDecimal(value: Double): String {
        val tenths = (value * 10.0).roundToLong()
        val sign = if (tenths < 0) "-" else ""
        val magnitude = abs(tenths)
        return "$sign${magnitude / 10}.${magnitude % 10}"
    }

    private fun outOfTen(source: String, name: String, value: Double?): DisplayRating? {
        if (value == null || !value.isFinite() || value <= 0.0 || value > 10.0) return null
        return DisplayRating(
            source = source,
            name = name,
            score = value * 10.0,
            display = formatOneDecimal(value),
        )
    }
}

/** This title's ratings row; see [ExternalRatings.forTitle]. */
fun ItemDetail.titleRatings(): List<DisplayRating> =
    ExternalRatings.forTitle(ratings, ratingImdb, ratingTmdb)
