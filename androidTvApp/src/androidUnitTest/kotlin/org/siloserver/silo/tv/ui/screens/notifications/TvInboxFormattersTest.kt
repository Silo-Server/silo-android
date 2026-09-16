package org.siloserver.silo.tv.ui.screens.notifications

import org.siloserver.silo.model.notifications.NotificationRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors androidApp's `InboxFormattersTest` — these pure helpers are
 * intentionally duplicated TV-side (see [TvInboxFormatters]) until they are
 * hoisted to a shared module.
 */
class TvInboxFormattersTest {

    private val now = Instant.parse("2026-06-12T12:00:00Z")

    private fun row(
        id: String = "n1",
        rawType: String = "episode.available",
        seriesId: String? = null,
        episodeId: String? = null,
        libraryId: Int? = null,
        seriesTitle: String = "",
        episodeTitle: String = "",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        posterUrl: String = "",
        posterThumbhash: String = "",
        createdAt: String = "2026-06-12T11:59:30Z",
        readAt: String? = null,
    ) = NotificationRow(
        id = id,
        rawType = rawType,
        profileId = "p1",
        libraryId = libraryId?.toString(),
        seriesId = seriesId,
        episodeId = episodeId,
        seriesTitle = seriesTitle,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        posterUrl = posterUrl,
        posterThumbhash = posterThumbhash,
        createdAt = createdAt,
        readAt = readAt,
    )

    @Test
    fun episodeRowProducesRichUnreadCardWithSeriesTarget() {
        val model = tvInboxCardModel(
            row(
                rawType = "episode.available",
                seriesId = "series-1",
                episodeId = "ep-1",
                seriesTitle = "The Show",
                episodeTitle = "The One",
                seasonNumber = 1,
                episodeNumber = 4,
                posterUrl = "https://example/p.jpg",
            ),
            now,
        )

        assertTrue(model.isRich)
        assertEquals("The Show", model.title)
        assertEquals("S1E4 • The One", model.subtitle)
        assertEquals("series-1", model.targetContentId)
        assertTrue(model.isUnread)
    }

    @Test
    fun readRowIsNotUnread() {
        val model = tvInboxCardModel(
            row(readAt = "2026-06-12T11:59:45Z"),
            now,
        )
        assertFalse(model.isUnread)
    }

    @Test
    fun unknownTypeIsNotRichAndGetsGenericTitle() {
        val model = tvInboxCardModel(
            row(rawType = "system.maintenance", seriesTitle = "Ignored", posterUrl = "https://x/p.jpg"),
            now,
        )
        assertFalse(model.isRich)
        assertEquals("System", model.title)
    }

    @Test
    fun knownNonEpisodeTypesGetFriendlyTitles() {
        assertEquals("Request fulfilled", fallbackTitleForType("request.fulfilled"))
        assertEquals("Webhook disabled", fallbackTitleForType("webhook.auto_disabled"))
        assertEquals("Notification", fallbackTitleForType(""))
    }

    @Test
    fun episodeFallsBackToTargetWhenNoSeriesId() {
        val model = tvInboxCardModel(
            row(seriesId = null, episodeId = "ep-9"),
            now,
        )
        assertEquals("ep-9", model.targetContentId)

        val libModel = tvInboxCardModel(
            row(seriesId = null, episodeId = null, libraryId = 7),
            now,
        )
        assertEquals("7", libModel.targetContentId)
    }

    @Test
    fun episodeWithoutSeriesTitleUsesDefaultTitle() {
        val model = tvInboxCardModel(
            row(seriesTitle = "", posterUrl = "https://x/p.jpg"),
            now,
        )
        assertTrue(model.isRich)
        assertEquals("New episode available", model.title)
    }

    @Test
    fun relativeTimeBuckets() {
        assertEquals("now", relativeTime("2026-06-12T11:59:30Z", now))
        assertEquals("5m", relativeTime("2026-06-12T11:55:00Z", now))
        assertEquals("2h", relativeTime("2026-06-12T10:00:00Z", now))
        assertEquals("3d", relativeTime("2026-06-09T12:00:00Z", now))
    }

    @Test
    fun relativeTimeOlderThanWeekIsAbsoluteDate() {
        val label = relativeTime("2026-06-02T12:00:00Z", now)
        assertFalse(label.endsWith("d") && label.length <= 4)
        assertTrue(label.isNotBlank())
    }

    @Test
    fun unparseableTimestampDegradesToEmpty() {
        assertEquals("", relativeTime("not-a-date", now))
    }
}
