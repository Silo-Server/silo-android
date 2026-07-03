package org.siloserver.silo.model.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a download record. Mirrors the server's `downloadResponse`
 * struct in `silo-server/internal/api/handlers/downloads.go:9-22` verbatim
 * (snake_case via @SerialName).
 *
 * `status` and `kind` are kept as raw strings so an unknown literal from a
 * newer server build doesn't fail decoding; clients map to [DownloadStatus] /
 * [DownloadKind] via `fromWire`.
 */
@Serializable
data class DownloadRecord(
    val id: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("media_file_id") val mediaFileId: Int,
    @SerialName("file_size") val fileSize: Long = 0L,
    @SerialName("bytes_sent") val bytesSent: Long = 0L,
    val kind: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    /** Requested quality preset (wire string, see [DownloadQuality]). Null on
     *  older servers / sidecars written before quality selection existed. */
    val quality: String? = null,
    /** Quality the server actually delivers (may differ from [quality] when
     *  the source is already below the requested bitrate). */
    @SerialName("effective_quality") val effectiveQuality: String? = null,
    @SerialName("delivery_format") val deliveryFormat: String? = null,
    @SerialName("target_bitrate_kbps") val targetBitrateKbps: Int? = null,
)

/**
 * List response wrapper — matches the server's `downloadsListResponse`
 * (`downloads.go:26-28`).
 */
@Serializable
data class DownloadsListResponse(
    val downloads: List<DownloadRecord> = emptyList(),
)

/**
 * POST /api/v1/downloads body. Either `episodeId` or `fileId` is set on
 * top of the always-required `contentId`. `series = true` requests batch
 * download of all episodes for a series content id (server expands and
 * returns one DownloadRecord per file under a shared batchId).
 */
@Serializable
data class DownloadRequest(
    @SerialName("content_id") val contentId: String,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("file_id") val fileId: Int? = null,
    val series: Boolean = false,
    /** Requested quality preset (wire string, see [DownloadQuality]). Must be
     *  one of the presets advertised in [DownloadCapability]; series batch
     *  requests are original-only per the server contract. */
    val quality: String? = null,
)

/**
 * GET /api/v1/downloads/capability response. Server-advertised download
 * feature gate; mirrors the Apple client's `DownloadCapability` including
 * the legacy `formats` decode fallback (older servers sent the preset list
 * under `formats` instead of `quality_presets`).
 */
@Serializable
data class DownloadCapability(
    val enabled: Boolean = false,
    @SerialName("download_allowed") val downloadAllowed: Boolean = false,
    @SerialName("quality_presets") val qualityPresets: List<String> = emptyList(),
    @SerialName("transcode_enabled") val transcodeEnabled: Boolean = false,
    @SerialName("transcode_user_allowed") val transcodeUserAllowed: Boolean = false,
    @SerialName("season_download") val seasonDownload: Boolean = false,
    @SerialName("series_monitoring") val seriesMonitoring: Boolean = false,
    @SerialName("monitoring_modes") val monitoringModes: List<String> = emptyList(),
    /** Legacy key for [qualityPresets] — see [effectivePresets]. */
    val formats: List<String>? = null,
) {
    /** qualityPresets → formats → [original], mirroring Apple's decode fallback. */
    val effectivePresets: List<String>
        get() = qualityPresets.ifEmpty { formats?.takeIf { it.isNotEmpty() } ?: listOf(DownloadQuality.Original.wire) }

    /** Downloads are usable only when the feature is on AND this user may download. */
    val isUsable: Boolean get() = enabled && downloadAllowed
}

/**
 * Public quality presets for a managed download. Only values advertised in
 * [DownloadCapability.effectivePresets] may be requested; the server enforces.
 */
enum class DownloadQuality(val wire: String, val displayName: String) {
    Original("original", "Original"),
    TwentyMbps("20mbps", "20 Mbps"),
    TenMbps("10mbps", "10 Mbps"),
    FiveMbps("5mbps", "5 Mbps"),
    TwoMbps("2mbps", "2 Mbps"),
    OneMbps("1mbps", "1 Mbps");

    companion object {
        fun fromWire(value: String?): DownloadQuality? =
            entries.firstOrNull { it.wire == value?.lowercase() }
    }
}

/**
 * Quality actually sent in a create request, mirroring Apple's
 * `resolvedDownloadQuality` + `DownloadSettings.resolvedFormat`:
 * requested-if-allowed → stored-default-if-allowed → original.
 */
fun resolveDownloadQuality(
    requested: String?,
    allowedPresets: List<String>,
    storedDefault: String,
): String = when {
    requested != null && requested in allowedPresets -> requested
    storedDefault in allowedPresets -> storedDefault
    else -> DownloadQuality.Original.wire
}

/**
 * Client-side status enum. Mirrors the server's `Status` field in the
 * `downloads` table (`migrations/042_downloads.up.sql`). Wire strings are
 * lowercased; unknown values resolve to [Unknown] so a server-side enum
 * extension doesn't crash the client.
 */
enum class DownloadStatus(val wire: String) {
    Queued("queued"),
    /** Server is producing a remux/transcode artifact — file GET not serveable yet. */
    Preparing("preparing"),
    /** Transcoded artifact is serveable; the client hasn't started the file GET. */
    Ready("ready"),
    Downloading("downloading"),
    Completed("completed"),
    Failed("failed"),
    Cancelled("cancelled"),
    Unknown("");

    companion object {
        fun fromWire(value: String?): DownloadStatus =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: Unknown
    }
}

/**
 * `direct` = browser one-shot serve (no persistent server record).
 * `queued` = tracked record visible in the user's downloads list.
 */
enum class DownloadKind(val wire: String) {
    Direct("direct"),
    Queued("queued"),
    Unknown("");

    companion object {
        fun fromWire(value: String?): DownloadKind =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: Unknown
    }
}

/** Convenience: type-safe accessor. */
fun DownloadRecord.statusEnum(): DownloadStatus = DownloadStatus.fromWire(status)

/** Convenience: type-safe accessor. */
fun DownloadRecord.kindEnum(): DownloadKind = DownloadKind.fromWire(kind)
