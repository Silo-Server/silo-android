package org.siloserver.silo.model.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The artwork part of `GET /api/v2/downloads/{id}/manifest`: the proxy URLs to
 * fetch once while online and the inline thumbhashes.
 *
 * For an episode entry `poster` is the episode's own image and
 * `series_poster` is the parent series poster. The `series_poster` fields exist
 * only on servers that name the series poster in episode manifests; older
 * servers and movie manifests omit them.
 */
@Serializable
data class OfflineManifestArtwork(
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("series_poster_thumbhash") val seriesPosterThumbhash: String? = null,
    @SerialName("artwork_urls") val artworkUrls: OfflineManifestArtworkUrls = OfflineManifestArtworkUrls(),
)

@Serializable
data class OfflineManifestArtworkUrls(
    val poster: String? = null,
    @SerialName("series_poster") val seriesPoster: String? = null,
)

/** Artwork saved beside a download, plus the thumbhashes the manifest listed. */
data class OfflineArtworkFiles(
    /** Absolute path of the saved `poster` image, or null when none was saved. */
    val posterPath: String? = null,
    /** Absolute path of the saved `series_poster` image, or null when none was saved. */
    val seriesPosterPath: String? = null,
    val posterThumbhash: String? = null,
    val seriesPosterThumbhash: String? = null,
)

private val offlineArtworkJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** Decodes the manifest's artwork subset; null for a body that is not a manifest object. */
fun decodeOfflineManifestArtwork(body: String): OfflineManifestArtwork? =
    runCatching { offlineArtworkJson.decodeFromString(OfflineManifestArtwork.serializer(), body) }.getOrNull()

/** Artwork kinds saved with a download, as named by the manifest and the proxy route. */
const val OFFLINE_ARTWORK_POSTER = "poster"
const val OFFLINE_ARTWORK_SERIES_POSTER = "series_poster"

/**
 * Only the same-server managed-download artwork proxy path for [kind]
 * (`/api/v2/downloads/{id}/artwork/{kind}`) is fetched. The request carries the
 * download owner's credentials, so an absolute or foreign reference in a
 * manifest must never be followed.
 */
fun isOfflineArtworkFetchUrl(url: String, kind: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith(OFFLINE_ARTWORK_DOWNLOADS_PREFIX)) return false
    if (trimmed.contains("://") || trimmed.contains("..") || trimmed.contains('\\')) return false
    if (trimmed.contains('?') || trimmed.contains('#')) return false
    val segments = trimmed.substring(OFFLINE_ARTWORK_DOWNLOADS_PREFIX.length).split('/')
    return segments.size == 3 && segments[0].isNotEmpty() && segments[1] == "artwork" && segments[2] == kind
}

private const val OFFLINE_ARTWORK_DOWNLOADS_PREFIX = "/api/v2/downloads/"
