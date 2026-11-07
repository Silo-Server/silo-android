package org.siloserver.silo.catalog.sort

/**
 * The media a sort list is for. A field applies to a scope when the server
 * can order that media by it (Latest Episode Added only orders series).
 */
enum class CatalogSortScope {
    Movie,
    Series,
    Episode,
    Audiobook,
    Ebook,
    Manga,
    ;

    companion object {
        val Video: Set<CatalogSortScope> = setOf(Movie, Series, Episode)
        val Media: Set<CatalogSortScope> = Video + setOf(Audiobook, Ebook)
    }
}

/**
 * Which fields a sort list offers, matching the web client's relevance scope:
 *
 * - [Any] lists every field (the web's cross-library catalog, favorites, and
 *   collections, which send no scope).
 * - [AllVideo] lists only fields that order movies, series, and episodes
 *   alike (a mixed library with no media type picked).
 * - [Only] lists the fields that order one scope (a movie or series library,
 *   or one side of a mixed library).
 */
sealed interface CatalogSortRelevance {
    data object Any : CatalogSortRelevance
    data object AllVideo : CatalogSortRelevance
    data class Only(val scope: CatalogSortScope) : CatalogSortRelevance

    companion object {
        /**
         * The relevance for a library's sort list. [mediaScope] is the
         * `movie` / `series` side a mixed library is browsed on, if any.
         */
        fun forLibrary(libraryType: String?, mediaScope: String? = null): CatalogSortRelevance {
            val type = libraryType?.trim()?.lowercase().orEmpty()
            return when (type) {
                "movie", "movies", "film" -> Only(CatalogSortScope.Movie)
                "series", "show", "shows", "tv" -> Only(CatalogSortScope.Series)
                "audiobook", "audiobooks" -> Only(CatalogSortScope.Audiobook)
                "manga" -> Only(CatalogSortScope.Manga)
                "ebook", "ebooks", "book", "books", "comic", "comics", "reading" -> Only(CatalogSortScope.Ebook)
                else -> when (mediaScope?.trim()?.lowercase()) {
                    "movie" -> Only(CatalogSortScope.Movie)
                    "series" -> Only(CatalogSortScope.Series)
                    else -> AllVideo
                }
            }
        }
    }
}

/** How a field names its two directions ("A–Z", "Newest", "Longest", ...). */
enum class CatalogSortDirectionStyle(val ascending: String, val descending: String) {
    Alphabetical("A–Z", "Z–A"),
    Chronological("Oldest", "Newest"),
    Length("Shortest", "Longest"),
    Magnitude("Lowest", "Highest"),
    Count("Fewest", "Most"),
}

/**
 * The catalog sort fields, in the web client's order and with its names and
 * default directions (`web/src/lib/querySortOptions.ts` in silo-server).
 * [wireValue] is the server's sort field.
 */
enum class CatalogSortField(
    val wireValue: String,
    val label: String,
    val defaultOrder: String,
    /** Ordered by this profile's own watching, so only offered where the server sorts per viewer. */
    val personalized: Boolean,
    val scopes: Set<CatalogSortScope>,
    val directionStyle: CatalogSortDirectionStyle,
    /** Offered only while the server shows this rating source. */
    val ratingSource: String? = null,
    private val readingLabel: String? = null,
) {
    Title("title", "Title", "asc", false, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Alphabetical),
    DateAdded("added_at", "Date Added", "desc", false, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Chronological),
    ReleaseDate("release_date", "Release Date", "desc", false, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Chronological),
    LatestEpisodeAirDate(
        "last_air_date",
        "Latest Episode Air Date",
        "desc",
        false,
        setOf(CatalogSortScope.Series, CatalogSortScope.Episode),
        CatalogSortDirectionStyle.Chronological,
    ),
    LatestEpisodeAdded("latest_episode_added", "Latest Episode Added", "desc", false, setOf(CatalogSortScope.Series), CatalogSortDirectionStyle.Chronological),
    Year("year", "Year", "desc", false, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Chronological),
    ContentRating("content_rating", "Content Rating", "asc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Alphabetical),
    Duration("runtime", "Duration", "desc", false, CatalogSortScope.Media, CatalogSortDirectionStyle.Length),
    ImdbRating("rating_imdb", "IMDb Rating", "desc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Magnitude),
    TmdbRating("rating_tmdb", "TMDB Rating", "desc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Magnitude),
    RtCriticRating("rating_rt_critic", "RT Critic Rating", "desc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Magnitude, ratingSource = "rt_critic"),
    RtAudienceRating("rating_rt_audience", "RT Audience Rating", "desc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Magnitude, ratingSource = "rt_audience"),
    Resolution("resolution", "Resolution", "desc", false, CatalogSortScope.Video, CatalogSortDirectionStyle.Magnitude),
    Bitrate("bitrate", "Bitrate", "desc", false, CatalogSortScope.Media, CatalogSortDirectionStyle.Magnitude),
    Progress("progress", "Progress", "desc", true, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Magnitude),
    DateViewed("date_viewed", "Date Viewed", "desc", true, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Chronological, readingLabel = "Date Read"),
    Plays("plays", "Plays", "desc", true, CatalogSortScope.Media + CatalogSortScope.Manga, CatalogSortDirectionStyle.Count, readingLabel = "Reads"),
    Author("author", "Author", "asc", false, setOf(CatalogSortScope.Audiobook, CatalogSortScope.Ebook, CatalogSortScope.Manga), CatalogSortDirectionStyle.Alphabetical),
    Narrator("narrator", "Narrator", "asc", false, setOf(CatalogSortScope.Audiobook), CatalogSortDirectionStyle.Alphabetical),
    Series("series", "Series", "asc", false, setOf(CatalogSortScope.Audiobook, CatalogSortScope.Ebook), CatalogSortDirectionStyle.Alphabetical),
    ;

    /** This field's name in a list for [relevance]: ebook and manga lists say "Date Read" and "Reads". */
    fun labelFor(relevance: CatalogSortRelevance): String {
        val reading = relevance == CatalogSortRelevance.Only(CatalogSortScope.Ebook) ||
            relevance == CatalogSortRelevance.Only(CatalogSortScope.Manga)
        return if (reading) readingLabel ?: label else label
    }

    /** "A–Z", "Newest", ... for [order]. */
    fun directionLabel(order: String): String =
        if (order == "asc") directionStyle.ascending else directionStyle.descending

    fun appliesTo(relevance: CatalogSortRelevance): Boolean = when (relevance) {
        CatalogSortRelevance.Any -> true
        CatalogSortRelevance.AllVideo -> scopes.containsAll(CatalogSortScope.Video)
        is CatalogSortRelevance.Only -> relevance.scope in scopes
    }

    companion object {
        /** The field for a server sort value. Legacy aliases map as the web client maps them. */
        fun fromWire(value: String?): CatalogSortField? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            val canonical = when (normalized) {
                "sort_title" -> "title"
                "recently_added" -> "added_at"
                "rating" -> "rating_imdb"
                else -> normalized
            }
            return entries.firstOrNull { it.wireValue == canonical }
        }

        /**
         * The fields one sort list offers, in display order.
         *
         * [shownRatingSources] is the server's shown rating sources. A rating
         * sort whose source is not shown is left out. [keep], the field the
         * list is sorted by now, is always listed, so a restored sort the
         * list would not otherwise offer stays visible and can be changed.
         */
        fun options(
            relevance: CatalogSortRelevance,
            includePersonalized: Boolean,
            shownRatingSources: Set<String>,
            keep: CatalogSortField? = null,
        ): List<CatalogSortField> = entries.filter { field ->
            field == keep || (
                (includePersonalized || !field.personalized) &&
                    field.appliesTo(relevance) &&
                    (field.ratingSource == null || field.ratingSource in shownRatingSources)
                )
        }
    }
}
