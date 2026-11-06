package org.siloserver.silo.tv.ui.screens.notifications

import android.content.res.Resources
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationType
import java.time.Instant
import java.time.ZoneId
import android.view.View
import org.siloserver.silo.tv.ui.components.bidiIsolated

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
/**
 * Card text, resolved when the card is drawn ([resolve]) so it follows an
 * in-place app language change. Mirrors the phone's `InboxText`.
 */
internal sealed interface TvInboxText {
    /** Server text shown as is: a title, or a type word derived from the wire type. */
    data class Plain(val text: String) : TvInboxText

    /** A catalog string with its arguments. */
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : TvInboxText

    /** "S1E4 • The One": the episode tag (when both numbers are known) and its title. */
    data class Episode(val season: Int?, val episode: Int?, val title: String?) : TvInboxText

    /** An absolute "MMM d" date, for a row older than a week. */
    data class Date(val instant: Instant) : TvInboxText
}

internal fun TvInboxText.resolve(resources: Resources): String = when (this) {
    is TvInboxText.Plain -> text
    is TvInboxText.Resource -> resources.getString(id, *args.toTypedArray())
    is TvInboxText.Episode -> {
        val tag = season?.let { s -> episode?.let { e -> resources.getString(CommonR.string.catalog_episodeTag, LocaleFormat.integer(s), LocaleFormat.integer(e)) } }
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        when {
            tag == null -> title.orEmpty()
            title == null -> tag
            else -> "$tag • ${bidiIsolated(title, rtl)}"
        }
    }
    is TvInboxText.Date -> formatAbsoluteDate(instant)
}

internal data class TvInboxCardModel(
    val id: String,
    val isRich: Boolean,
    val title: TvInboxText,
    val subtitle: TvInboxText?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val relativeTime: TvInboxText?,
    val isUnread: Boolean,
    val targetContentId: String?,
)

/**
 * Folds a [NotificationRow] into a [TvInboxCardModel]. [now] is the reference
 * instant for the relative-time label (injected for deterministic tests).
 *
 * Episode rows render as a rich card (poster + series title + SxEy/episode
 * subtitle); every other type — including unrecognized ones — renders a
 * posterless fallback card with a friendly type label.
 */
internal fun tvInboxCardModel(row: NotificationRow, now: Instant): TvInboxCardModel {
    val isEpisode = row.type == NotificationType.EpisodeAvailable
    val seriesTitle = row.seriesTitle.ifBlank { null }
    val episodeTitle = row.episodeTitle.ifBlank { null }
    val posterUrl = row.posterUrl.ifBlank { null }
    val posterThumbhash = row.posterThumbhash.ifBlank { null }

    val hasTag = row.seasonNumber != null && row.episodeNumber != null
    val subtitle = if (isEpisode) {
        TvInboxText.Episode(row.seasonNumber, row.episodeNumber, episodeTitle).takeIf { hasTag || episodeTitle != null }
    } else {
        (episodeTitle ?: seriesTitle)?.let(TvInboxText::Plain)
    }

    return TvInboxCardModel(
        id = row.id,
        isRich = isEpisode && (posterUrl != null || seriesTitle != null),
        title = if (isEpisode) {
            seriesTitle?.let(TvInboxText::Plain) ?: TvInboxText.Resource(CommonR.string.notifications_type_newEpisode)
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
internal fun fallbackTitleForType(type: String): TvInboxText = when (type) {
    "request.fulfilled" -> TvInboxText.Resource(CommonR.string.notifications_type_requestFulfilled)
    "webhook.auto_disabled" -> TvInboxText.Resource(CommonR.string.notifications_type_webhookDisabled)
    else -> type.substringBefore('.')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
        .takeIf { it.isNotBlank() }
        ?.let(TvInboxText::Plain)
        ?: TvInboxText.Resource(CommonR.string.notifications_type_generic)
}

/**
 * Relative-time label keyed off the row's ISO-8601 `created_at`. Buckets to
 * now/m/h/d up to a week, then an absolute "MMM d" date. Unparseable or future
 * timestamps degrade gracefully ("now" / null).
 */
internal fun relativeTime(createdAtIso: String, now: Instant): TvInboxText? {
    val createdAt = parseInstantOrNull(createdAtIso) ?: return null
    val delta = (now.epochSecond - createdAt.epochSecond).coerceAtLeast(0)
    return when {
        delta < 60 -> TvInboxText.Resource(CommonR.string.notifications_age_now)
        delta < 3_600 -> TvInboxText.Resource(CommonR.string.notifications_age_minutes, listOf((delta / 60).toInt()))
        delta < 86_400 -> TvInboxText.Resource(CommonR.string.notifications_age_hours, listOf((delta / 3_600).toInt()))
        delta < 7 * 86_400 -> TvInboxText.Resource(CommonR.string.notifications_age_days, listOf((delta / 86_400).toInt()))
        else -> TvInboxText.Date(createdAt)
    }
}

private fun parseInstantOrNull(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: Exception) {
    null
}

private fun formatAbsoluteDate(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(
        LocaleFormat.dateFormatter("MMMd", "MMM d"),
    )
