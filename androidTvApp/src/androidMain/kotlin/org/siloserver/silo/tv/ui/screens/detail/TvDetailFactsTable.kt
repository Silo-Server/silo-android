package org.siloserver.silo.tv.ui.screens.detail

import java.time.LocalDate
import java.util.Locale
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentFormatLocale
import org.siloserver.silo.common.i18n.uppercaseForUi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR

/**
 * Key/value grid that lists directors, writers, studios, networks, countries
 * and dates beneath the detail page hero. Mirrors `TVDetailFactsSection` with
 * tvOS geometry mapped to Android TV's half-scale canvas and text lifted to a
 * 10-foot readability floor.
 */
@Composable
internal fun TvDetailFactsTable(
    detail: ItemDetail,
    modifier: Modifier = Modifier,
) {
    val locale = currentFormatLocale()
    val facts = remember(detail, locale) { detail.assembleFacts(locale) }
    if (facts.isEmpty()) return

    Column(
        modifier = modifier.widthIn(max = 700.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(fact.label).uppercaseForUi(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal fun ItemDetail.hasTvDetailFacts(): Boolean = assembleFacts().isNotEmpty()

private data class FactRow(@StringRes val label: Int, val value: String)

private fun ItemDetail.assembleFacts(locale: Locale = LocaleFormat.locale()): List<FactRow> {
    val out = mutableListOf<FactRow>()

    crew.creditNames(jobs = listOf("director"))?.let {
        out += FactRow(CommonR.string.detail_director, it)
    }
    crew.creditNames(jobs = listOf("writer", "screenplay", "story", "teleplay"))?.let {
        out += FactRow(
            if (crew.hasJob("screenplay")) CommonR.string.detail_writer else CommonR.string.detail_writtenBy,
            it,
        )
    }
    studios.takeIf { it.isNotEmpty() }?.let {
        out += FactRow(CommonR.string.detail_studio, it.take(3).joinToString(", "))
    }
    networks.takeIf { it.isNotEmpty() }?.let {
        out += FactRow(CommonR.string.detail_network, it.take(3).joinToString(", "))
    }
    countries.takeIf { it.isNotEmpty() }?.let {
        out += FactRow(CommonR.string.detail_country, it.take(3).joinToString(", "))
    }
    // Full genre list (the hero eyebrow only shows the first two); mirrors the
    // phone's "Genres" details row.
    genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
        out += FactRow(CommonR.string.detail_genres, it.joinToString(", "))
    }
    if (type.equals("episode", ignoreCase = true)) {
        TvDetailMetadata.abbreviatedDate(airDate ?: releaseDate, locale = locale)?.let { out += FactRow(CommonR.string.detail_aired, it) }
    } else {
        formattedDate(releaseDate, locale)?.let { out += FactRow(CommonR.string.detail_released, it) }
        formattedDate(firstAirDate, locale)?.let { out += FactRow(CommonR.string.detail_firstAired, it) }
        formattedDate(lastAirDate, locale)?.let { out += FactRow(CommonR.string.detail_lastAired, it) }
    }
    return out
}

private fun List<CrewMember>.creditNames(jobs: List<String>): String? {
    val tokens = jobs.map { it.lowercase() }
    val names = filter { member ->
        val job = member.job?.lowercase().orEmpty()
        tokens.any { it in job }
    }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    if (names.isEmpty()) return null
    val joined = names.take(3).joinToString(", ")
    return if (names.size > 3) "$joined, …" else joined
}

private fun List<CrewMember>.hasJob(token: String): Boolean =
    any { (it.job?.lowercase()?.contains(token) ?: false) }

/** "July 8, 2026" in US English, in [locale]'s order elsewhere; text that isn't a date as is. */
private fun formattedDate(raw: String?, locale: Locale): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = trimmed.split("-")
    if (parts.size < 3) return trimmed
    val year = parts[0].toIntOrNull() ?: return trimmed
    val month = parts[1].toIntOrNull() ?: return trimmed
    val day = parts[2].take(2).toIntOrNull() ?: return trimmed
    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return trimmed
    return date.format(LocaleFormat.dateFormatter("yMMMMd", "MMMM d, y", locale, padYear = false))
}
