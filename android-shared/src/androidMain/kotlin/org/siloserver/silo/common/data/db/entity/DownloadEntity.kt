package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room projection of a [org.siloserver.silo.model.download.DownloadSidecar]
 * (server [org.siloserver.silo.model.download.DownloadRecord] + the catalog
 * metadata needed to render and play a download offline).
 *
 * Identity is `(serverId, profileId, mediaFileId)` to match how downloads are
 * scoped on disk today (`DownloadStorage`). [recordId] (the server record id)
 * is kept uniquely indexed so server-driven reconciliation can look a row up
 * by record without re-deriving the scope.
 *
 * Field set mirrors the sidecar so the Downloads tab and offline player need
 * no network round-trip: title/poster/thumbhash, series/season/episode,
 * overview/author/narrator, duration, and chapters (stored as
 * [chaptersJson] — a serialized `List<VersionChapter>` — to avoid a TypeConverter
 * in schema v1). Byte/progress and lifecycle fields (`fileSize`, `bytesSent`,
 * `kind`, `createdAt`, `completedAt`) come from the record.
 */
@Entity(
    tableName = "downloads",
    primaryKeys = ["serverId", "profileId", "mediaFileId"],
    indices = [
        Index(value = ["serverId", "profileId", "status"]),
        Index(value = ["serverId", "profileId", "contentId"]),
        Index(value = ["recordId"], unique = true),
    ],
)
data class DownloadEntity(
    val serverId: String,
    val profileId: String,
    val mediaFileId: Int,
    val recordId: String,
    val contentId: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val year: Int?,
    val seriesTitle: String?,
    /** Stable content id of the parent series/show (TV) or manga series — the
     *  rename-safe grouping key for the tiered Downloads tab. Null for non-tiered
     *  leaves (movies). Books group by [author]; music by artist/album (later). */
    val seriesContentId: String? = null,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val fileName: String?,
    val container: String?,
    val localUri: String?,
    val mediaType: String,
    val overview: String?,
    val author: String?,
    val narrator: String?,
    val durationSeconds: Double?,
    /** Serialized `List<VersionChapter>`; null/absent for non-chaptered media. */
    val chaptersJson: String?,
    /** Serialized marker inventory; null means it has not been fetched. */
    val markerSegmentsJson: String? = null,
    // Mirror DownloadStatus wire values (lowercase): queued/downloading/completed/
    // failed/cancelled (DownloadModels.kt); plus a local-only "stale" for imported
    // rows whose bytes are missing (Task 6).
    val status: String,
    val kind: String,
    val fileSize: Long,
    val bytesSent: Long,
    val createdAt: String,
    val completedAt: String?,
    val updatedAtMs: Long,
    /** HTTP validator (strong ETag, else Last-Modified) captured at download
     *  start so a resumed transfer can send `If-Range` — the server then returns
     *  206 (source unchanged → safe to append) or 200 (source changed → restart).
     *  Null = no validator captured (resume falls back to a fresh download). */
    val resumeValidator: String? = null,
    /** Requested download quality wire string (`DownloadQuality.wire`). Persisted
     *  because the Downloads tab falls back to the stored record when there is no
     *  live server record — offline, or once the download has completed. */
    val quality: String? = null,
    /** Quality the server actually delivered after any compatibility fallback;
     *  may differ from [quality]. Same offline-fallback reason as above. */
    val effectiveQuality: String? = null,
)
