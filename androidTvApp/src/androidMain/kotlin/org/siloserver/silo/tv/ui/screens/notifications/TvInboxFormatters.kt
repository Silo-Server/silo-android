package org.siloserver.silo.tv.ui.screens.notifications

import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation model for one TV inbox card. Pure: derived entirely from a
 * [NotificationRow] plus the current time, so it is fully unit-testable without
 * Compose. [isRich] picks the poster card vs. the posterless fallback card.
 *
 * NOTE (duplication): this mirrors `InboxCardModel`/`inboxCardModel`/
 * `relativeTime`/`fallbackTitleForType` in the androidApp module
 * (`org.siloserver.silo.android.ui.screens.notifications.InboxFormatters`).
 * androidApp is NOT a dependency of androidTvApp (separate application modules),
 * so the pure helpers are duplicated here intentionally. A future cleanup should
 * hoist these into `commonMain` (or `android-shared`) and delete both copies.
 */
internal data class TvInboxCardModel(
    val id: String,
    val isRich: Boolean,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val relativeTime: String,
    val isUnread: Boolean,
    val targetContentId: String?,
)

/**
 * Folds a [NotificationRow] into a [TvInboxCardModel]. [now] is the reference
 * instant for the relative-time label (injected for deterministic tests).
 *
 * Episode rows render as a rich card (poster + series title + SxEy/episode
 * subtitle); every other type renders a posterless fallback card.
 * `watchlist.available` names the arrived title; the rest — including
 * unrecognized types — use a friendly type label.
 */
internal fun tvInboxCardModel(row: NotificationRow, now: Instant): TvInboxCardModel {
    val isEpisode = row.type == NotificationType.EpisodeAvailable
    val isWatchlist = row.type == NotificationType.WatchlistAvailable
    val seriesTitle = row.seriesTitle.ifBlank { null }
    val episodeTitle = row.episodeTitle.ifBlank { null }
    val posterUrl = row.posterUrl.ifBlank { null }
    val posterThumbhash = row.posterThumbhash.ifBlank { null }

    val sxey = row.seasonNumber?.let { s -> row.episodeNumber?.let { e -> "S${s}E$e" } }
    val subtitle = when {
        isEpisode -> listOfNotNull(sxey, episodeTitle).joinToString(" • ").takeIf { it.isNotBlank() }
        // The title already names the arrived item.
        isWatchlist -> null
        else -> episodeTitle ?: seriesTitle
    }
    val title = when {
        isEpisode -> seriesTitle ?: "New episode available"
        isWatchlist -> watchlistAvailableTitle(seriesTitle)
        else -> fallbackTitleForType(row.rawType)
    }

    return TvInboxCardModel(
        id = row.id,
        isRich = isEpisode && (posterUrl != null || seriesTitle != null),
        title = title,
        subtitle = subtitle,
        posterUrl = posterUrl,
        posterThumbhash = posterThumbhash,
        relativeTime = relativeTime(row.createdAt, now),
        isUnread = !row.isRead,
        targetContentId = row.seriesId ?: row.episodeId ?: row.libraryId?.toString(),
    )
}

/** Title for a `watchlist.available` row; [seriesTitle] names the arrived movie or series. */
internal fun watchlistAvailableTitle(seriesTitle: String?): String =
    seriesTitle?.let { "$it is now available" } ?: "A title on your watchlist is now available"

/** Friendly label for a wire `type` string, with a generic fallback. */
internal fun fallbackTitleForType(type: String): String = when (type) {
    "request.fulfilled" -> "Request fulfilled"
    "webhook.auto_disabled" -> "Webhook disabled"
    else -> type.substringBefore('.')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Notification" }
}

/**
 * Relative-time label keyed off the row's ISO-8601 `created_at`. Buckets to
 * now/m/h/d up to a week, then an absolute "MMM d" date. Unparseable or future
 * timestamps degrade gracefully ("now" / empty).
 */
internal fun relativeTime(createdAtIso: String, now: Instant): String {
    val createdAt = parseInstantOrNull(createdAtIso) ?: return ""
    val delta = (now.epochSecond - createdAt.epochSecond).coerceAtLeast(0)
    return when {
        delta < 60 -> "now"
        delta < 3_600 -> "${delta / 60}m"
        delta < 86_400 -> "${delta / 3_600}h"
        delta < 7 * 86_400 -> "${delta / 86_400}d"
        else -> formatAbsoluteDate(createdAt)
    }
}

private fun parseInstantOrNull(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: Exception) {
    null
}

private fun formatAbsoluteDate(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()),
    )
