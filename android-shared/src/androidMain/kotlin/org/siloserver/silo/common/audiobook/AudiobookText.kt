package org.siloserver.silo.common.audiobook

import android.content.res.Resources
import org.siloserver.silo.audiobook.AudiobookDetailFormatting
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.getStringWithNames
import org.siloserver.silo.model.catalog.ItemDetail
import android.view.View
import java.time.LocalDate
import org.siloserver.silo.common.i18n.isolated

/**
 * The audiobook wording Apple's `BookDetailPresentation` and
 * `TVAudiobookViewModel` build, for the phone and TV detail pages and players.
 */
object AudiobookText {

    /** The title without the series name and volume the server baked into it. */
    fun title(detail: ItemDetail): String =
        AudiobookDetailFormatting.cleanTitle(detail.title, detail.audiobook?.series?.name)

    /** "Black Rose Chronicles · Book 2", "… · Book 5 of 5", or the bare series name. */
    fun seriesLine(resources: Resources, detail: ItemDetail): String? {
        val series = detail.audiobook?.series ?: return null
        val name = series.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val index = AudiobookDetailFormatting.seriesIndex(
            contentId = detail.contentId,
            rawTitle = detail.title,
            entries = series.entries.map { it.contentId to it.seriesIndex },
        ) ?: return name
        val total = AudiobookDetailFormatting.volume(detail.title).total
        return if (total != null && total >= index && total > 1) {
            resources.getString(CommonR.string.detail_audiobook_seriesLineOf, isolate(resources, name), index, total)
        } else {
            resources.getString(CommonR.string.detail_audiobook_seriesLine, isolate(resources, name), index)
        }
    }

    /** "A, B & 3 more", or the whole list joined naturally. */
    fun people(resources: Resources, names: List<String>, visible: Int): String? {
        val summary = AudiobookDetailFormatting.peopleSummary(names, visible) ?: return null
        if (summary.remaining == 0) return AudiobookDetailFormatting.naturalJoin(summary.shown)
        return resources.getQuantityString(
            CommonR.plurals.detail_audiobook_peopleMore,
            summary.remaining,
            summary.remaining,
            isolate(resources, summary.shown.joinToString(", ")),
        )
    }

    fun authors(resources: Resources, detail: ItemDetail, visible: Int): String? =
        people(resources, detail.audiobook?.authors.orEmpty().map { it.name }, visible)

    fun narrators(resources: Resources, detail: ItemDetail, visible: Int): String? =
        people(resources, detail.audiobook?.narrators.orEmpty().map { it.name }, visible)

    /** The phone hero's credit line: "By A & B · Narrated by N". */
    fun creditLine(resources: Resources, detail: ItemDetail): String? {
        val pieces = listOfNotNull(
            authors(resources, detail, visible = 3)
                ?.let { resources.getStringWithNames(CommonR.string.detail_audiobook_byAuthors, it) },
            narrators(resources, detail, visible = 2)
                ?.let { resources.getStringWithNames(CommonR.string.detail_narratedBy, it) },
        )
        return pieces.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The player's line under the title: every author, comma-joined. */
    fun playerSubtitle(detail: ItemDetail): String? =
        detail.audiobook?.authors.orEmpty().map { it.name }.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.joinToString(", ")

    /**
     * The whole book's length: the larger of the server's total and the
     * stitched part lengths, so a stale short total can't make a position in
     * the last parts look finished.
     */
    fun totalDurationSeconds(detail: ItemDetail, stitchedSeconds: Double?): Double {
        val parts = stitchedSeconds ?: 0.0
        detail.audiobook?.totalDurationSeconds?.takeIf { it > 0 }?.let { return maxOf(it.toDouble(), parts) }
        detail.userData?.durationSeconds?.takeIf { it > 0 }?.let { return maxOf(it, parts) }
        return parts
    }

    /** "5h 18m", "42m", "45s"; empty for nothing. Apple `PlayerTimeFormatter.formatRuntime`. */
    fun runtime(resources: Resources, seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return ""
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 && m > 0 -> resources.getString(
                CommonR.string.catalog_runtimeHoursMinutes,
                LocaleFormat.integer(h),
                LocaleFormat.integer(m),
            )
            h > 0 -> resources.getString(CommonR.string.catalog_runtimeHours, LocaleFormat.integer(h))
            m > 0 -> resources.getString(CommonR.string.catalog_runtimeMinutesShort, LocaleFormat.integer(m))
            else -> resources.getString(CommonR.string.catalog_runtimeSeconds, LocaleFormat.integer(total))
        }
    }

    /** "1:02:15" or "17:14". Apple `PlayerTimeFormatter.formatHMS`. */
    fun clock(seconds: Double): String {
        val total = if (seconds.isNaN()) 0L else seconds.toLong().coerceAtLeast(0L)
        return LocaleFormat.clock(total / 3600, (total % 3600) / 60, total % 60)
    }

    /**
     * Apple `audiobookFacts`, label then value: who wrote and reads it, the
     * publisher, when it was released, how long it runs, and the file format.
     */
    fun facts(resources: Resources, detail: ItemDetail, totalSeconds: Double, partCount: Int): List<Pair<String, String>> = buildList {
        fun personCount(names: List<String>) = names.flatMap { it.split(",") }.count { it.isNotBlank() }
        val authors = detail.audiobook?.authors.orEmpty().map { it.name }
        people(resources, authors, visible = 3)?.let {
            add(resources.getQuantityString(CommonR.plurals.detail_facts_author, personCount(authors)) to it)
        }
        val narrators = detail.audiobook?.narrators.orEmpty().map { it.name }
        people(resources, narrators, visible = 3)?.let {
            add(resources.getQuantityString(CommonR.plurals.detail_facts_narrator, personCount(narrators)) to it)
        }
        detail.audiobook?.publisher?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add(resources.getString(CommonR.string.detail_facts_publisher) to it)
        }
        val released = detail.releaseDate?.takeIf { it.isNotBlank() }?.let(::longDate)
            ?: detail.year?.takeIf { it > 0 }?.let { LocaleFormat.integer(it) }
        released?.let { add(resources.getString(CommonR.string.detail_released) to it) }
        runtime(resources, totalSeconds).takeIf { it.isNotEmpty() }?.let {
            add(resources.getString(CommonR.string.detail_facts_length) to it)
        }
        val primary = detail.versions.firstOrNull()
        val format = listOfNotNull(
            primary?.codecAudio?.takeIf { it.isNotBlank() }?.uppercase(),
            primary?.container?.takeIf { it.isNotBlank() }?.uppercase(),
            partCount.takeIf { it > 1 }?.let {
                resources.getQuantityString(CommonR.plurals.detail_audiobook_partCount, it, it, LocaleFormat.integer(it))
            },
        )
        if (format.isNotEmpty()) add(resources.getString(CommonR.string.detail_facts_format) to format.joinToString(" · "))
    }

    /** "January 1, 2015" in the interface language's order; null for an unparseable date. */
    private fun longDate(iso: String): String? = runCatching {
        LocalDate.parse(iso.take(10)).format(LocaleFormat.dateFormatter("yMMMMd", "MMMM d, yyyy"))
    }.getOrNull()

    /** Isolates a name in a right-to-left interface, as `getStringWithNames` does. */
    private fun isolate(resources: Resources, name: String): String =
        isolated(arrayOf(name), rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)[0]
}
