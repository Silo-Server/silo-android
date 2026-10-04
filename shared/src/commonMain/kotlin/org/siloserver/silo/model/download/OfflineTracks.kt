package org.siloserver.silo.model.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.catalog.AudioTrack

/**
 * The part of `GET /api/v2/downloads/{id}/manifest` the offline player needs:
 * the audio tracks of the DELIVERED file and the subtitle sidecars to fetch.
 *
 * `audio_tracks[].index` is the track's position inside the downloaded file,
 * not a source-file ordinal: a prepared (remux/transcode) MP4 carries every
 * source audio track in source order, a legacy prepared file carries one, and
 * an original download is the source file itself.
 */
@Serializable
data class OfflineManifestTracks(
    @SerialName("delivery_format") val deliveryFormat: String? = null,
    @SerialName("selected_audio_track_index") val selectedAudioTrackIndex: Int? = null,
    @SerialName("audio_tracks") val audioTracks: List<AudioTrack> = emptyList(),
    val subtitles: List<OfflineManifestSubtitle> = emptyList(),
)

@Serializable
data class OfflineManifestSubtitle(
    val language: String? = null,
    val title: String? = null,
    val format: String? = null,
    val forced: Boolean = false,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("fetch_url") val fetchUrl: String = "",
    /**
     * Opaque revision of a stored (`downloaded:{id}`) or external
     * (`external:{index}`) subtitle. It changes whenever the bytes [fetchUrl]
     * serves can change: the server retimed the subtitle (a sync, or a timing
     * set or reset), or an external file was edited on disk. Absent for
     * embedded tracks and for an external file the server cannot read.
     */
    val revision: String? = null,
)

/**
 * Track data persisted with a [DownloadSidecar] so a download can offer its
 * audio and subtitle choices with no network. Absent on downloads completed
 * before this existed; those keep the legacy offline behaviour.
 */
@Serializable
data class OfflineTrackInfo(
    /**
     * The downloaded file's audio tracks in file order. The server numbers
     * them 0..n-1 in list order, so [AudioTrack.index] and the list position
     * are both the track's position among the file's Media3 audio groups.
     */
    val audioTracks: List<AudioTrack> = emptyList(),
    /** Position (into [audioTracks]) to select by default. */
    val selectedAudioTrackIndex: Int? = null,
    val subtitles: List<OfflineSubtitleFile> = emptyList(),
    /**
     * True for server-prepared files, whose re-encoded tracks are selected by
     * position. An original download is the source file with its source
     * codecs; Media3 does not report every container's audio groups in file
     * order (Matroska can differ), so originals select by catalog identity.
     */
    val audioByPosition: Boolean = false,
) {
    /** The default audio position: the manifest's pick, else the default flag, else the first. */
    fun defaultAudioPosition(): Int {
        if (audioTracks.isEmpty()) return 0
        selectedAudioTrackIndex?.takeIf { it in audioTracks.indices }?.let { return it }
        return audioTracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    }
}

/** A subtitle sidecar fetched once at download time and kept beside the download. */
@Serializable
data class OfflineSubtitleFile(
    /** Absolute path of the local file. Its extension matches [format]. */
    val path: String,
    /** Canonical format: `srt`, `vtt`, `ass`, `ssa`, `pgs`, or `ttml`. */
    val format: String,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    /** The manifest `fetch_url` the file came from; null on captures older than this field. */
    val fetchUrl: String? = null,
    /** The manifest `revision` of the fetched bytes, when the manifest gave one. */
    val revision: String? = null,
)

/**
 * Saved sidecars whose subtitle the [manifest] lists at a different revision,
 * paired with that revision. Captures that predate [OfflineSubtitleFile.fetchUrl]
 * cannot be matched to a manifest row and are never refreshed. An external
 * ref names a sidecar by its position, which shifts when the files next to the
 * media change, so a row whose language or format no longer matches the saved
 * file is left alone.
 */
fun OfflineTrackInfo.subtitlesWithNewRevision(
    manifest: OfflineManifestTracks,
): List<Pair<OfflineSubtitleFile, String>> {
    val listed = manifest.subtitles
        .filter { it.revision != null }
        .associateBy { it.fetchUrl.trim() }
    return subtitles.mapNotNull { saved ->
        val row = saved.fetchUrl?.let(listed::get) ?: return@mapNotNull null
        val revision = row.revision ?: return@mapNotNull null
        if (!saved.language.isNullOrBlank() && !row.language.isNullOrBlank() &&
            !saved.language.equals(row.language, ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        if (offlineSubtitleFormat(row.format)?.let { it != saved.format } == true) return@mapNotNull null
        if (revision == saved.revision) null else saved to revision
    }
}

private val offlineManifestJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** Decodes the manifest subset; null for a body that is not a manifest object. */
fun decodeOfflineManifestTracks(body: String): OfflineManifestTracks? =
    runCatching { offlineManifestJson.decodeFromString(OfflineManifestTracks.serializer(), body) }.getOrNull()

/** Builds the persisted track data from a manifest and the sidecars saved from it. */
fun OfflineManifestTracks.toOfflineTrackInfo(savedSubtitles: List<OfflineSubtitleFile>): OfflineTrackInfo =
    OfflineTrackInfo(
        audioTracks = audioTracks,
        selectedAudioTrackIndex = selectedAudioTrackIndex?.takeIf { it in audioTracks.indices },
        subtitles = savedSubtitles,
        audioByPosition = !deliveryFormat.equals(DELIVERY_FORMAT_ORIGINAL, ignoreCase = true),
    )

private const val DELIVERY_FORMAT_ORIGINAL = "original"

/**
 * Canonical local format for a manifest subtitle `format`, or null when the
 * player cannot mount it as a sidecar (so it is not downloaded at all).
 */
fun offlineSubtitleFormat(format: String?): String? =
    when (format?.trim()?.lowercase()?.removePrefix(".")) {
        "srt", "subrip" -> "srt"
        "vtt", "webvtt" -> "vtt"
        "ass" -> "ass"
        "ssa" -> "ssa"
        "sup", "pgs", "hdmv_pgs_subtitle" -> "pgs"
        "ttml", "dfxp" -> "ttml"
        else -> null
    }

/** File extension for a canonical [offlineSubtitleFormat] value. */
fun offlineSubtitleExtension(format: String): String = when (format) {
    "pgs" -> "sup"
    else -> format
}

/**
 * Only same-server managed-download subtitle proxy paths are fetched. The URL
 * is authenticated with the download owner's credentials, so an absolute or
 * foreign reference in a manifest must never be followed.
 */
fun isOfflineSubtitleFetchUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith(OFFLINE_DOWNLOADS_PREFIX)) return false
    if (trimmed.contains("://") || trimmed.contains("..") || trimmed.contains('\\')) return false
    return trimmed.substring(OFFLINE_DOWNLOADS_PREFIX.length).contains("/subtitles/")
}

private const val OFFLINE_DOWNLOADS_PREFIX = "/api/v2/downloads/"
