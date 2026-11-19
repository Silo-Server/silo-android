package org.siloserver.silo.model.download

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadSubscriptionTargetType(val wire: String) {
    Series("series"),
    Season("season"),
    AudiobookSeries("audiobook_series"),
    Author("author"),
    Collection("collection"),
    ;

    companion object {
        fun fromWire(value: String?): DownloadSubscriptionTargetType =
            entries.firstOrNull { it.wire == value?.lowercase()?.trim() } ?: Series
    }
}

@Serializable
enum class DownloadSubscriptionMediaKind(val wire: String) {
    Video("video"),
    Audio("audio"),
    Reading("reading"),
    ;

    companion object {
        fun fromWire(value: String?): DownloadSubscriptionMediaKind =
            entries.firstOrNull { it.wire == value?.lowercase()?.trim() } ?: Video
    }
}

@Serializable
data class DownloadSubscription(
    val id: String,
    val serverId: String,
    /** Saved account ([org.siloserver.silo.model.server.AccountEntry.localAccountId]); '' = before accounts. */
    val accountId: String,
    val profileId: String,
    val targetType: DownloadSubscriptionTargetType,
    val targetId: String,
    val displayTitle: String,
    val mediaKind: DownloadSubscriptionMediaKind,
    val quality: DownloadQuality = DownloadQuality.Original,
    val wifiOnly: Boolean = true,
    val enabled: Boolean = true,
    val includeExisting: Boolean = false,
    val keepUnwatchedLimit: Int = 3,
    val deleteWatchedAfterDays: Int = 7,
    val createdAt: Long,
    val updatedAt: Long,
    val lastEvaluatedAt: Long? = null,
    val lastError: String? = null,
)
