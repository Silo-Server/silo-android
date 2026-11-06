package org.siloserver.silo.android.ui.screens.notifications

import android.content.res.Resources
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.notifications.NotificationType
import org.siloserver.silo.model.notifications.NotificationRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Card text, resolved when the card is drawn ([resolve]) so it follows an
 * in-place app language change.
 */
internal sealed interface InboxText {
    /** Server text shown as is: a title, or a type word derived from the wire type. */
    data class Plain(val text: String) : InboxText

    /** A catalog string with its arguments. */
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : InboxText

    /** "S1E4 • The One": the episode tag (when both numbers are known) and its title. */
    data class Episode(val season: Int?, val episode: Int?, val title: String?) : InboxText

    /** An absolute "MMM d" date, for a row older than a week. */
    data class Date(val instant: Instant) : InboxText
}

internal fun InboxText.resolve(resources: Resources): String = when (this) {
    is InboxText.Plain -> text
    is InboxText.Resource -> resources.getString(id, *args.toTypedArray())
    is InboxText.Episode -> {
        val tag = season?.let { s -> episode?.let { e -> resources.getString(CommonR.string.catalog_episodeTag, s.toString(), e.toString()) } }
        listOfNotNull(tag, title).joinToString(" • ")
    }
    is InboxText.Date -> formatAbsoluteDate(instant)
}

/**
 * Presentation model for one inbox card. Pure: derived entirely from a
 * [NotificationRow] plus the current time, so it is fully unit-testable without
 * Compose. [isRich] picks the poster card vs. the posterless fallback card.
 */
internal data class InboxCardModel(
    val id: String,
    val isRich: Boolean,
    val title: InboxText,
    val subtitle: InboxText?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val relativeTime: InboxText?,
    val isUnread: Boolean,
    val targetContentId: String?,
)

/**
 * Folds a [NotificationRow] into an [InboxCardModel]. [now] is the reference
 * instant for the relative-time label (injected for deterministic tests).
 *
 * Episode rows render as a rich card (poster + series title + SxEy/episode
 * subtitle); every other type — including unrecognized ones — renders a
 * posterless fallback card with a friendly type label.
 */
internal fun inboxCardModel(row: NotificationRow, now: Instant): InboxCardModel {
    val isEpisode = row.type == NotificationType.EpisodeAvailable
    val seriesTitle = row.seriesTitle.ifBlank { null }
    val episodeTitle = row.episodeTitle.ifBlank { null }
    val posterUrl = row.posterUrl.ifBlank { null }
    val posterThumbhash = row.posterThumbhash.ifBlank { null }

    val hasTag = row.seasonNumber != null && row.episodeNumber != null
    val subtitle = if (isEpisode) {
        InboxText.Episode(row.seasonNumber, row.episodeNumber, episodeTitle).takeIf { hasTag || episodeTitle != null }
    } else {
        (episodeTitle ?: seriesTitle)?.let(InboxText::Plain)
    }

    return InboxCardModel(
        id = row.id,
        isRich = isEpisode && (posterUrl != null || seriesTitle != null),
        title = if (isEpisode) {
            seriesTitle?.let(InboxText::Plain) ?: InboxText.Resource(CommonR.string.notifications_type_newEpisode)
        } else {
            fallbackTitleForType(row.rawType)
        },
        subtitle = subtitle,
        posterUrl = posterUrl,
        posterThumbhash = posterThumbhash,
        relativeTime = relativeTime(row.createdAt, now),
        isUnread = !row.isRead,
        targetContentId = row.seriesId ?: row.episodeId ?: row.libraryId?.toString(),
    )
}

/** Friendly label for a wire `type` string, with a generic fallback. */
internal fun fallbackTitleForType(type: String): InboxText = when (type) {
    "request.fulfilled" -> InboxText.Resource(CommonR.string.notifications_type_requestFulfilled)
    "webhook.auto_disabled" -> InboxText.Resource(CommonR.string.notifications_type_webhookDisabled)
    else -> type.substringBefore('.')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
        .takeIf { it.isNotBlank() }
        ?.let(InboxText::Plain)
        ?: InboxText.Resource(CommonR.string.notifications_type_generic)
}

/**
 * Relative-time label keyed off the row's ISO-8601 `created_at`. Buckets to
 * now/m/h/d up to a week, then an absolute "MMM d" date. Unparseable or future
 * timestamps degrade gracefully ("now" / null).
 */
internal fun relativeTime(createdAtIso: String, now: Instant): InboxText? {
    val createdAt = parseInstantOrNull(createdAtIso) ?: return null
    val delta = (now.epochSecond - createdAt.epochSecond).coerceAtLeast(0)
    return when {
        delta < 60 -> InboxText.Resource(CommonR.string.notifications_age_now)
        delta < 3_600 -> InboxText.Resource(CommonR.string.notifications_age_minutes, listOf((delta / 60).toInt()))
        delta < 86_400 -> InboxText.Resource(CommonR.string.notifications_age_hours, listOf((delta / 3_600).toInt()))
        delta < 7 * 86_400 -> InboxText.Resource(CommonR.string.notifications_age_days, listOf((delta / 86_400).toInt()))
        else -> InboxText.Date(createdAt)
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
