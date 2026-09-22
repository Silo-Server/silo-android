package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.catalog.*

// Wire projections of fields consumed by the existing detail surfaces.
// Numeric domain IDs are checked at the adapter boundary, never coerced.
@Serializable
internal data class ItemDetailReadV2(
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
    val cast: List<CastMember>,
    val crew: List<CrewMember>,
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
    @SerialName("user_data") val userData: LeafItemUserDataReadV2? = null,
    @SerialName("user_rating") val userRating: Int? = null,
    val versions: List<FileVersionReadV2>,
    @SerialName("playback_variants") val playbackVariants: List<PlaybackVariantReadV2> = emptyList(),
    val subtitles: List<SubtitleInfo>,
    @SerialName("overlay_summary") val overlaySummary: OverlaySummary? = null,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    val recap: TimeRange? = null,
    val preview: TimeRange? = null,
    val audiobook: org.siloserver.silo.model.audiobook.AudiobookMetadata? = null,
    val book: org.siloserver.silo.model.book.BookMetadata? = null,
    val ebook: org.siloserver.silo.model.ebook.EbookMetadata? = null,
    val videos: List<ItemVideo>? = null,
    val extras: List<ItemExtra>? = null,
) {
    fun toDomain() = ItemDetail(
        contentId = contentId,
        type = type,
        status = status,
        title = title,
        sortTitle = sortTitle,
        originalTitle = originalTitle,
        originalLanguage = originalLanguage,
        showStatus = showStatus,
        year = year,
        overview = overview,
        pendingTranslationLanguage = pendingTranslationLanguage,
        tagline = tagline,
        runtime = runtime,
        contentRating = contentRating,
        genres = genres,
        ratingImdb = ratingImdb,
        ratingTmdb = ratingTmdb,
        ratingRtCritic = ratingRtCritic,
        ratingRtAudience = ratingRtAudience,
        imdbId = imdbId,
        tmdbId = tmdbId,
        tvdbId = tvdbId,
        cast = cast,
        crew = crew,
        studios = studios,
        networks = networks,
        countries = countries,
        lockedFields = lockedFields,
        releaseDate = releaseDate,
        firstAirDate = firstAirDate,
        lastAirDate = lastAirDate,
        posterUrl = posterUrl,
        posterThumbhash = posterThumbhash,
        backdropUrl = backdropUrl,
        backdropThumbhash = backdropThumbhash,
        logoUrl = logoUrl,
        seasonCount = seasonCount,
        seriesId = seriesId,
        seriesTitle = seriesTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeCount = episodeCount,
        airDate = airDate,
        isSpecials = isSpecials,
        userData = userData?.toDomain(),
        userRating = userRating,
        versions = versions.map { it.toDomain() },
        playbackVariants = playbackVariants.map { it.toDomain() },
        subtitles = subtitles,
        overlaySummary = overlaySummary,
        intro = intro,
        credits = credits,
        recap = recap,
        preview = preview,
        audiobook = audiobook,
        book = book,
        ebook = ebook,
        videos = videos,
        extras = extras,
    )
}

@Serializable
internal data class PlaybackVariantReadV2(
    @SerialName("variant_id") val variantId: String,
    @SerialName("edition_raw") val editionRaw: String? = null,
    @SerialName("edition_key") val editionKey: String? = null,
    @SerialName("presentation_kind") val presentationKind: String? = null,
    @SerialName("presentation_group_key") val presentationGroupKey: String? = null,
    @SerialName("part_count") val partCount: Int = 0,
    @SerialName("total_duration") val totalDuration: Double? = null,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("default_file_id") val defaultFileId: String? = null,
    val parts: List<PlaybackVariantPartReadV2> = emptyList(),
) {
    fun toDomain() = PlaybackVariant(
        variantId = variantId,
        editionRaw = editionRaw,
        editionKey = editionKey,
        presentationKind = presentationKind,
        presentationGroupKey = presentationGroupKey,
        partCount = partCount,
        totalDuration = totalDuration,
        defaultFileId = defaultFileId?.let(::checkedIntId),
        parts = parts.map { it.toDomain() },
    )
}

@Serializable
internal data class PlaybackVariantPartReadV2(
    @SerialName("part_index") val partIndex: Int = 0,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("default_file_id") val defaultFileId: String? = null,
    @SerialName("total_duration") val totalDuration: Double? = null,
    val versions: List<FileVersionReadV2> = emptyList(),
) {
    fun toDomain() = PlaybackVariantPart(
        partIndex = partIndex,
        defaultFileId = defaultFileId?.let(::checkedIntId),
        totalDuration = totalDuration,
        versions = versions.map { it.toDomain() },
    )
}

@Serializable
internal data class FileVersionReadV2(
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("edition_raw") val editionRaw: String? = null,
    @SerialName("edition_key") val editionKey: String? = null,
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
    @SerialName("effective_audio_track_index") val effectiveAudioTrackIndex: Int? = null,
    @SerialName("subtitle_tracks") val subtitleTracks: List<SubtitleTrack>? = null,
    val chapters: List<VersionChapter>? = null,
    @SerialName("presentation_kind") val presentationKind: String? = null,
    @SerialName("presentation_group_key") val presentationGroupKey: String? = null,
    @SerialName("presentation_part_index") val presentationPartIndex: Int? = null,
    @SerialName("presentation_part_total") val presentationPartTotal: Int? = null
) {
    fun toDomain() = FileVersion(
        fileId = checkedIntId(fileId),
        fileName = fileName,
        filePath = filePath,
        editionRaw = editionRaw,
        editionKey = editionKey,
        resolution = resolution,
        codecVideo = codecVideo,
        codecAudio = codecAudio,
        hdr = hdr,
        container = container,
        fileSize = fileSize,
        duration = duration,
        bitrate = bitrate,
        addedAt = addedAt,
        videoTracks = videoTracks,
        audioTracks = audioTracks,
        effectiveAudioTrackIndex = effectiveAudioTrackIndex,
        subtitleTracks = subtitleTracks,
        chapters = chapters,
        presentationKind = presentationKind,
        presentationGroupKey = presentationGroupKey,
        presentationPartIndex = presentationPartIndex,
        presentationPartTotal = presentationPartTotal,
    )
}

@Serializable
internal data class LeafItemUserDataReadV2(
    val played: Boolean = false,
    @SerialName("is_in_progress") val isInProgress: Boolean? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("last_file_id") val lastFileId: String? = null,
    @SerialName("last_resolution") val lastResolution: String? = null,
    @SerialName("last_hdr") val lastHdr: Boolean? = null,
    @SerialName("last_codec_video") val lastCodecVideo: String? = null
) {
    fun toDomain() = LeafItemUserData(
        played = played,
        isInProgress = isInProgress,
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        lastFileId = lastFileId?.let(::checkedIntId),
        lastResolution = lastResolution,
        lastHdr = lastHdr,
        lastCodecVideo = lastCodecVideo,
    )
}

@Serializable
internal data class EpisodeListItemReadV2(
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
    @SerialName("user_data") val userData: LeafItemUserDataReadV2? = null,
    val files: List<EpisodeFileReadV2> = emptyList()
) {
    fun toDomain() = EpisodeListItem(
        contentId = contentId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
        overview = overview,
        airDate = airDate,
        runtime = runtime,
        imdbId = imdbId,
        tmdbId = tmdbId,
        tvdbId = tvdbId,
        stillUrl = stillUrl,
        stillThumbhash = stillThumbhash,
        userData = userData?.toDomain(),
        files = files.map { it.toDomain() },
    )
}

@Serializable
internal data class EpisodeFileReadV2(
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("file_id") val fileId: String,
    val resolution: String? = null,
    @SerialName("codec_video") val codecVideo: String? = null,
    val hdr: Boolean = false,
    @SerialName("audio_channels") val audioChannels: Int = 0,
    val container: String? = null,
    @SerialName("file_size") val fileSize: Long = 0
) {
    fun toDomain() = EpisodeFile(
        fileId = checkedIntId(fileId),
        resolution = resolution,
        codecVideo = codecVideo,
        hdr = hdr,
        audioChannels = audioChannels,
        container = container,
        fileSize = fileSize,
    )
}

@Serializable
internal data class PersonReadV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
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
) {
    fun toDomain() = Person(
        id = checkedLongId(id),
        name = name,
        bio = bio,
        birthDate = birthDate,
        deathDate = deathDate,
        birthplace = birthplace,
        homepage = homepage,
        photoUrl = photoUrl,
        photoThumbhash = photoThumbhash,
        tmdbId = tmdbId,
        imdbId = imdbId,
        tvdbId = tvdbId,
        plexGuid = plexGuid,
    )
}

@Serializable
internal data class DetailCollectionReadV2<T>(val items: List<T>, val page: PageInfo? = null)

private fun checkedLongId(value: String): Long {
    require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "An identifier is not a decimal string." }
    return requireNotNull(value.toLongOrNull()?.takeIf { it > 0 }) { "An identifier exceeds the supported range." }
}

private fun checkedIntId(value: String): Int {
    val id = checkedLongId(value)
    require(id <= Int.MAX_VALUE) { "An identifier exceeds the supported range." }
    return id.toInt()
}

/** JSON numbers are not v2 IDs, even if the JSON decoder accepts them as text. */
internal object DetailStringIdSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("DetailStringId", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (value == null || !value.isString) throw SerializationException("Expected a string identifier.")
        return value.content
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
