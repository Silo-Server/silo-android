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
    // Quality contract (issue #20 §5). `quality` is what the client asked for;
    // `effective_quality` is what the server actually delivered after any
    // compatibility fallback; `delivery_format` is original/remux/transcode;
    // `target_bitrate_kbps` is 0 for original/remux, the cap for transcodes.
    // All nullable — older servers and ephemeral/batch rows may omit them.
    val quality: String? = null,
    @SerialName("effective_quality") val effectiveQuality: String? = null,
    @SerialName("delivery_format") val deliveryFormat: String? = null,
    @SerialName("target_bitrate_kbps") val targetBitrateKbps: Int? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val revision: Int? = null,
    @SerialName("status_event_at") val statusEventAt: String? = null,
)

/**
 * List response wrapper — matches the server's `downloadsListResponse`
 * (`downloads.go:26-28`).
 */
@Serializable
data class DownloadsListResponse(
    val downloads: List<DownloadRecord> = emptyList(),
    val skipped: List<SkippedDownload> = emptyList(),
)

@Serializable
data class SkippedDownload(
    @SerialName("episode_id") val episodeId: String,
    val reason: String,
)

/**
 * Client-side download quality presets. "Original" preserves the source file
 * and its public/discoverable filename. Bitrate presets prepare the client
 * contract for server-side transcoded downloads; until the server supports
 * them, older servers ignore these additive request fields.
 */
enum class DownloadQuality(
    val wire: String,
    val label: String,
    val targetBitrateKbps: Int?,
) {
    Original("original", "Original", null),
    Mbps20("20mbps", "20 Mbps", 20_000),
    Mbps10("10mbps", "10 Mbps", 10_000),
    Mbps5("5mbps", "5 Mbps", 5_000),
    Mbps2("2mbps", "2 Mbps", 2_000),
    Mbps1("1mbps", "1 Mbps", 1_000);

    companion object {
        fun fromWire(value: String?): DownloadQuality =
            entries.firstOrNull { it.wire == value?.lowercase()?.trim() } ?: Original
    }
}

/**
 * POST /api/v2/downloads body. Either `episodeId` or `fileId` is set on
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
    val quality: String? = null,
    @SerialName("target_bitrate_kbps") val targetBitrateKbps: Int? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val revision: Int? = null,
    @SerialName("status_event_at") val statusEventAt: String? = null,
)

/**
 * Client-side status enum. Mirrors the server's `Status` field in the
 * `downloads` table (`migrations/042_downloads.up.sql`). Wire strings are
 * lowercased; unknown values resolve to [Unknown] so a server-side enum
 * extension doesn't crash the client.
 */
enum class DownloadStatus(val wire: String) {
    // Non-original (remux/transcode) rows start `preparing` while the server
    // builds the artifact, flip to `ready` when it is servable, then follow
    // the same downloading→completed path as originals (issue #20 §5). A
    // client that maps these to Unknown treats a just-requested transcode as
    // "failed" and self-destructs the download, so they are first-class here.
    Preparing("preparing"),
    Ready("ready"),
    Queued("queued"),
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

/**
 * `GET /api/v2/capabilities/downloads` response — the server's per-account
 * download feature gate (issue #20 §3). Fetched at detail load / profile
 * switch so the quality picker offers only [qualityPresets] and never a
 * value the server will reject (a bitrate request against a transcode-disabled
 * account is a guaranteed 403 `transcode_disabled`). Mirrors Apple's
 * `DownloadCapability`. Every field is defaulted so an older server that
 * lacks the endpoint or a field still decodes leniently.
 */
@Serializable
data class DownloadCapability(
    val revision: String? = null,
    val state: String? = null,
    @SerialName("proxy_delivery") val proxyDelivery: Boolean = false,
    @SerialName("ordered_status") val orderedStatus: Boolean = false,
    val enabled: Boolean = false,
    @SerialName("download_allowed") val downloadAllowed: Boolean = false,
    @SerialName("quality_presets") val qualityPresets: List<String> = emptyList(),
    @SerialName("transcode_enabled") val transcodeEnabled: Boolean = false,
    @SerialName("transcode_user_allowed") val transcodeUserAllowed: Boolean = false,
    @SerialName("season_download") val seasonDownload: Boolean = false,
    @SerialName("series_monitoring") val seriesMonitoring: Boolean = false,
    @SerialName("monitoring_modes") val monitoringModes: List<String> = emptyList(),
) {
    /** Downloads are usable only when the feature is on AND this user may download. */
    val isUsable: Boolean get() = enabled && downloadAllowed && (revision == null || (revision.isNotBlank() && state == "available"))

    /**
     * The [DownloadQuality] presets to offer this user, in ladder order.
     * Filters the enum to the server's [qualityPresets], then drops every
     * bitrate preset unless transcode is BOTH server-enabled and permitted for
     * this user. Always returns at least [DownloadQuality.Original] so the
     * picker is never empty.
     */
    fun allowedQualities(): List<DownloadQuality> {
        val allowedWire = qualityPresets.map { it.lowercase().trim() }.toSet()
        val presets = DownloadQuality.entries.filter { it.wire in allowedWire }
        val gated = if (transcodeEnabled && transcodeUserAllowed) {
            presets
        } else {
            presets.filter { it == DownloadQuality.Original }
        }
        return gated.ifEmpty { listOf(DownloadQuality.Original) }
    }
}

/** Convenience: type-safe accessor. */
fun DownloadRecord.statusEnum(): DownloadStatus = DownloadStatus.fromWire(status)

/** Convenience: type-safe accessor. */
fun DownloadRecord.kindEnum(): DownloadKind = DownloadKind.fromWire(kind)
