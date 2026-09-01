package org.siloserver.silo.model.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Browse / Catalog ---

@Serializable
data class MediaItemUserState(
    val played: Boolean = false,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("in_watchlist") val inWatchlist: Boolean = false
)

/**
 * Tech-level overlay summary derived server-side from the best-ranked file
 * (see silo-server `internal/overlays/summary.go`). Wire keys match the server
 * JSON and Apple's `OverlaySummary` (which decodes via `.convertFromSnakeCase`,
 * so `audioChannels` ⇄ `audio_channels`, etc.). `audioChannels` arrives
 * pre-formatted (e.g. "5.1", "7.1", "Stereo").
 */
@Serializable
data class OverlaySummary(
    val resolution: String? = null,
    val hdr: String? = null,
    val audio: String? = null,
    @SerialName("audio_channels") val audioChannels: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    val container: String? = null,
    @SerialName("aspect_ratio") val aspectRatio: String? = null,
    @SerialName("release_type") val releaseType: String? = null,
    val edition: String? = null,
    @SerialName("multi_audio") val multiAudio: Boolean? = null,
    @SerialName("multi_sub") val multiSub: Boolean? = null
)

@Serializable
data class BrowseItem(
    @SerialName("content_id") val contentId: String,
    val type: String,
    val title: String,
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    @SerialName("content_rating") val contentRating: String? = null,
    val status: String? = null,
    @SerialName("rating_imdb") val ratingImdb: Double? = null,
    @SerialName("rating_tmdb") val ratingTmdb: Double? = null,
    @SerialName("rating_rt_critic") val ratingRtCritic: Int? = null,
    @SerialName("rating_rt_audience") val ratingRtAudience: Int? = null,
    val runtime: Int? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    @SerialName("show_status") val showStatus: String? = null,
    val overview: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("user_state") val userState: MediaItemUserState? = null,
    @SerialName("overlay_summary") val overlaySummary: OverlaySummary? = null
)

@Serializable
data class BrowseResponse(
    val total: Int = 0,
    @SerialName("total_exact") val totalExact: Boolean? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<BrowseItem> = emptyList()
)

@Serializable
data class CatalogResponse(
    val total: Int = 0,
    @SerialName("total_exact") val totalExact: Boolean? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<BrowseItem> = emptyList(),
    val source: String? = null,
    val title: String? = null,
    val snapshot: String? = null,
    /**
     * What the server actually sorted by. Sources with an intrinsic order
     * (library collections keep their manual / MDBList / smart order when no
     * `sort` is sent) echo the resolved field here, so a client that sent
     * nothing can still say what it is looking at.
     */
    @SerialName("effective_sort") val effectiveSort: CatalogEffectiveSort? = null
)

@Serializable
data class CatalogEffectiveSort(
    val field: String? = null,
    val order: String? = null,
)

data class CatalogQueryRule(
    val field: String,
    val op: String,
    val value: String,
    /** Range ops ("between") carry indexed values instead of [value] —
     *  encoded as value[0], value[1] on the wire (iOS CatalogQueryBuilder). */
    val values: List<String> = emptyList(),
)

data class CatalogQueryGroup(
    val match: String = "all",
    val rules: List<CatalogQueryRule> = emptyList(),
)

@Serializable
data class AudiobookGroup(
    val name: String,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("total_duration_seconds") val totalDurationSeconds: Long? = null,
    @SerialName("in_progress_count") val inProgressCount: Int = 0,
    @SerialName("finished_count") val finishedCount: Int = 0,
    @SerialName("poster_urls") val posterUrls: List<String> = emptyList(),
)

@Serializable
data class AudiobookGroupsResponse(
    val total: Int = 0,
    @SerialName("total_exact") val totalExact: Boolean? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val groups: List<AudiobookGroup> = emptyList(),
)

@Serializable
data class CatalogFiltersResponse(
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    @SerialName("content_ratings") val contentRatings: List<String> = emptyList(),
    val resolutions: List<String>? = null,
    @SerialName("audio_languages") val audioLanguages: List<String>? = null,
    @SerialName("subtitle_languages") val subtitleLanguages: List<String>? = null,
    @SerialName("original_languages") val originalLanguages: List<String>? = null,
    val authors: List<String>? = null,
    val narrators: List<String>? = null,
    val series: List<String>? = null
)

// --- Item Detail ---

@Serializable
data class ItemDetail(
    @SerialName("content_id") val contentId: String,
    val type: String,
    val status: String? = null,
    val title: String,
    @SerialName("sort_title") val sortTitle: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("show_status") val showStatus: String? = null,
    val year: Int = 0,
    val overview: String? = null,
    // Non-null while a viewer-facing description translation job is queued or
    // running for this item; clears when the translated overview lands.
    @SerialName("pending_translation_language") val pendingTranslationLanguage: String? = null,
    val tagline: String? = null,
    val runtime: Int = 0,
    @SerialName("content_rating") val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("rating_imdb") val ratingImdb: Double? = null,
    @SerialName("rating_tmdb") val ratingTmdb: Double? = null,
    @SerialName("rating_rt_critic") val ratingRtCritic: Int? = null,
    @SerialName("rating_rt_audience") val ratingRtAudience: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    @SerialName("locked_fields") val lockedFields: List<Int>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("season_count") val seasonCount: Int? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("is_specials") val isSpecials: Boolean? = null,
    @SerialName("user_data") val userData: LeafItemUserData? = null,
    /** The current user's personal star rating (1-5), when set. */
    @SerialName("user_rating") val userRating: Int? = null,
    val versions: List<FileVersion> = emptyList(),
    val subtitles: List<SubtitleInfo> = emptyList(),
    @SerialName("overlay_summary") val overlaySummary: OverlaySummary? = null,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    val recap: TimeRange? = null,
    val preview: TimeRange? = null,
    /** Populated only when [type] is "audiobook". Forward-compat — the
     *  server may stop returning it once a dedicated /api/v1/audiobooks
     *  endpoint lands; until then it rides on ItemDetail. */
    val audiobook: org.siloserver.silo.model.audiobook.AudiobookMetadata? = null,
    /** Legacy fallback for older servers that emitted book-like metadata. */
    val book: org.siloserver.silo.model.book.BookMetadata? = null,
    /** Populated only when [type] is "ebook" on current servers. */
    val ebook: org.siloserver.silo.model.ebook.EbookMetadata? = null,
    /** Remote provider trailers/teasers, pre-ordered by the server. */
    val videos: List<ItemVideo>? = null,
    /** Scanner-discovered local trailers and featurettes. */
    val extras: List<ItemExtra>? = null,
)

@Serializable
data class ItemVideo(
    val kind: String,
    val site: String,
    @SerialName("site_key") val siteKey: String,
    val name: String? = null,
    val language: String? = null,
    @SerialName("is_official") val isOfficial: Boolean = false,
)

@Serializable
data class ItemExtra(
    @SerialName("content_id") val contentId: String,
    val kind: String,
    val title: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("file_id") val fileId: Int? = null,
)

@Serializable
data class CastMember(
    val name: String,
    val character: String? = null,
    val order: Int = 0,
    @SerialName("person_id") val personId: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("plex_guid") val plexGuid: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("photo_thumbhash") val photoThumbhash: String? = null
)

@Serializable
data class CrewMember(
    val name: String,
    val job: String? = null,
    @SerialName("person_id") val personId: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("plex_guid") val plexGuid: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("photo_thumbhash") val photoThumbhash: String? = null
)

// --- File / Track Info ---

@Serializable
data class FileVersion(
    @SerialName("file_id") val fileId: Int,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    val resolution: String? = null,
    @SerialName("codec_video") val codecVideo: String? = null,
    @SerialName("codec_audio") val codecAudio: String? = null,
    val hdr: Boolean = false,
    val container: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    val duration: Double = 0.0,
    val bitrate: Int = 0,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("video_tracks") val videoTracks: List<VideoTrack>? = null,
    @SerialName("audio_tracks") val audioTracks: List<AudioTrack>? = null,
    // Server-resolved ordinal into audio_tracks for the track playback will
    // actually use when the user has made no explicit pick — the preview rung
    // between an explicit selection and the isDefault flag (Apple parity:
    // selected → effective → default → first).
    @SerialName("effective_audio_track_index") val effectiveAudioTrackIndex: Int? = null,
    @SerialName("subtitle_tracks") val subtitleTracks: List<SubtitleTrack>? = null,
    val chapters: List<VersionChapter>? = null,
    // --- Whole-book audiobook stitching (see org.siloserver.silo.audiobook.AudiobookTimeline) ---
    // The server has no concept of a whole book: it sends each audiobook file as an
    // individual FileVersion tagged `presentation_kind == "audiobook_part"` with a
    // `presentation_part_index` (pre-sorted by index). The client stitches these parts
    // into one virtual whole-book timeline. Defaults keep single-file items unchanged.
    @SerialName("presentation_kind") val presentationKind: String? = null,
    @SerialName("presentation_group_key") val presentationGroupKey: String? = null,
    @SerialName("presentation_part_index") val presentationPartIndex: Int? = null,
    @SerialName("presentation_part_total") val presentationPartTotal: Int? = null
)

/**
 * One chapter on a playable file version. Populated server-side by FFprobe
 * during ingest (`source = "embedded"` for chapters read out of MP4/MKV
 * metadata). The server normalizes overlaps, sorts out-of-order entries,
 * strips zero-length chapters, and generates fallback titles when needed —
 * the client renders whatever it receives.
 *
 * Shape mirrors the server's `VersionChapter` struct at
 * `silo-server/internal/catalog/detail.go:258-266` and Apple's
 * `VersionChapter` at `iosApp/Networking/Models.swift:537-545` exactly.
 */
@Serializable
data class VersionChapter(
    val index: Int = 0,
    val title: String = "",
    @SerialName("start_seconds") val startSeconds: Double = 0.0,
    @SerialName("end_seconds") val endSeconds: Double = 0.0,
    val source: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("thumbnail_thumbhash") val thumbnailThumbhash: String? = null,
)

@Serializable
data class VideoTrack(
    val index: Int = 0,
    val codec: String? = null,
    @SerialName("dolby_vision") val dolbyVision: String? = null,
    @SerialName("dv_profile") val dolbyVisionProfile: Int? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("frame_rate")
    @Serializable(with = NullableFrameRateSerializer::class)
    val frameRate: Double? = null,
    val bitrate: Int? = null,
    val hdr: Boolean = false,
    @SerialName("hdr_format") val hdrFormat: String? = null,
    val profile: String? = null,
    val level: String? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    @SerialName("color_space") val colorSpace: String? = null,
    @SerialName("color_primaries") val colorPrimaries: String? = null,
    @SerialName("color_transfer") val colorTransfer: String? = null,
    @SerialName("color_range") val colorRange: String? = null,
    val title: String? = null,
    val language: String? = null
)

@Serializable
data class AudioTrack(
    val index: Int = 0,
    val codec: String? = null,
    val channels: Int? = null,
    @SerialName("layout") val channelLayout: String? = null,
    val bitrate: Int? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    val language: String? = null,
    val title: String? = null,
    @SerialName("default") val isDefault: Boolean = false
)

@Serializable
data class SubtitleTrack(
    val index: Int = 0,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
    val external: Boolean = false,
    @SerialName("external_path") val externalPath: String? = null
)

@Serializable
data class SubtitleInfo(
    val source: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val forced: Boolean = false,
    val title: String? = null
)

@Serializable
data class TimeRange(
    val start: Double,
    val end: Double
)

// --- Seasons / Episodes ---

@Serializable
data class Season(
    @SerialName("content_id") val contentId: String,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("is_specials") val isSpecials: Boolean = false,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("user_data") val userData: SeasonUserData? = null
)

@Serializable
data class SeasonsResponse(
    val seasons: List<Season> = emptyList()
)

fun Season.isSpecialsForDisplay(): Boolean =
    isSpecials || seasonNumber == 0

fun List<Season>.sortedForDisplay(): List<Season> =
    sortedWith(
        // Match tvOS: numbered seasons first, Specials last.
        compareBy<Season> { it.isSpecialsForDisplay() }
            .thenBy { it.seasonNumber }
            .thenBy { it.title.orEmpty() }
            .thenBy { it.contentId },
    )

private fun List<Season>.selectedSeasonForDisplay(preferredSeasonNumber: Int?): Season? {
    return preferredSeasonNumber
        ?.let { preferred -> firstOrNull { it.seasonNumber == preferred } }
        // Open the Show overview while preloading the viewer's actual browse
        // target: Continue Watching first, then a partially watched season,
        // then the first not-yet-complete season. This lets the first Down land
        // on e.g. Season 3 Episode 1 exactly as tvOS does.
        ?: firstOrNull { (it.userData?.inProgressCount ?: 0) > 0 }
        ?: firstOrNull { season ->
            val watched = season.userData?.watchedCount ?: 0
            watched > 0 && watched < season.episodeCount
        }
        ?: firstOrNull { it.userData?.played != true }
        ?: firstOrNull()
}

fun List<Season>.initialSeasonForDisplay(preferredSeasonNumber: Int?): Season? =
    sortedForDisplay().selectedSeasonForDisplay(preferredSeasonNumber)

data class InitialSeasonDisplayPlan(
    val seasons: List<Season>,
    val selectedSeasonNumber: Int?,
) {
    val episodeRequestSeasonNumber: Int?
        get() = selectedSeasonNumber
}

fun List<Season>.initialSeasonDisplayPlan(
    preferredSeasonNumber: Int?,
): InitialSeasonDisplayPlan {
    val seasons = sortedForDisplay()
    return InitialSeasonDisplayPlan(
        seasons = seasons,
        selectedSeasonNumber = seasons
            .selectedSeasonForDisplay(preferredSeasonNumber)
            ?.seasonNumber,
    )
}

@Serializable
data class EpisodeListItem(
    @SerialName("content_id") val contentId: String,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int = 0,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("still_thumbhash") val stillThumbhash: String? = null,
    @SerialName("user_data") val userData: LeafItemUserData? = null,
    val files: List<EpisodeFile> = emptyList()
)

@Serializable
data class EpisodeFile(
    @SerialName("file_id") val fileId: Int,
    val resolution: String? = null,
    @SerialName("codec_video") val codecVideo: String? = null,
    val hdr: Boolean = false,
    @SerialName("audio_channels") val audioChannels: Int = 0,
    val container: String? = null,
    @SerialName("file_size") val fileSize: Long = 0
)

@Serializable
data class EpisodesResponse(
    val episodes: List<EpisodeListItem> = emptyList()
)

// --- User Data ---

@Serializable
data class LeafItemUserData(
    val played: Boolean = false,
    @SerialName("is_in_progress") val isInProgress: Boolean? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("last_file_id") val lastFileId: Int? = null,
    @SerialName("last_resolution") val lastResolution: String? = null,
    @SerialName("last_hdr") val lastHdr: Boolean? = null,
    @SerialName("last_codec_video") val lastCodecVideo: String? = null
)

@Serializable
data class SeasonUserData(
    val played: Boolean = false,
    @SerialName("watched_count") val watchedCount: Int = 0,
    @SerialName("unplayed_count") val unplayedCount: Int = 0,
    @SerialName("in_progress_count") val inProgressCount: Int = 0
)

// --- Watch Detail ---

@Serializable
data class WatchDetail(
    @SerialName("content_id") val contentId: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val versions: List<FileVersion> = emptyList(),
    val subtitles: List<SubtitleInfo> = emptyList(),
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    val recap: TimeRange? = null,
    val preview: TimeRange? = null,
    @SerialName("user_data") val userData: LeafItemUserData? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("effective_subtitle_language") val effectiveSubtitleLanguage: String? = null,
    @SerialName("effective_subtitle_mode") val effectiveSubtitleMode: String? = null,
    @SerialName("effective_show_forced_subtitles") val effectiveShowForcedSubtitles: Boolean? = null,
    // Optional forward-compatible artwork. Current servers expose these on
    // ItemDetail rather than WatchDetail, so playback clients must fall back
    // to the full catalog detail when these fields are absent.
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

// --- Person ---

@Serializable
data class Person(
    val id: Long,
    val name: String,
    val bio: String? = null,
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("death_date") val deathDate: String? = null,
    val birthplace: String? = null,
    val homepage: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("photo_thumbhash") val photoThumbhash: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    @SerialName("plex_guid") val plexGuid: String? = null
)

// --- Query Definition (for smart collections / catalog queries) ---

@Serializable
data class QueryDefinition(
    val groups: List<QueryGroup> = emptyList(),
    val sort: QuerySort? = null
)

@Serializable
data class QueryGroup(
    val operator: String = "and",
    val rules: List<QueryRule> = emptyList()
)

@Serializable
data class QueryRule(
    val field: String,
    val operator: String,
    val value: JsonElement? = null
)

@Serializable
data class QuerySort(
    val field: String,
    val order: String = "asc"
)
