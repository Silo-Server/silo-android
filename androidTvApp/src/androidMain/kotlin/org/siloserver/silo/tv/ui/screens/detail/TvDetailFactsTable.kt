package org.siloserver.silo.tv.ui.screens.detail

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
    val facts = remember(detail) { detail.assembleFacts() }
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
                    text = fact.label.uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp,
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(130.dp),
                )
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal fun ItemDetail.hasTvDetailFacts(): Boolean = assembleFacts().isNotEmpty()

private data class FactRow(val label: String, val value: String)

private fun ItemDetail.assembleFacts(): List<FactRow> {
    val out = mutableListOf<FactRow>()

    crew.creditNames(jobs = listOf("director"))?.let {
        out += FactRow("Director", it)
    }
    crew.creditNames(jobs = listOf("writer", "screenplay", "story", "teleplay"))?.let {
        out += FactRow(if (crew.hasJob("screenplay")) "Writer" else "Written by", it)
    }
    studios.takeIf { it.isNotEmpty() }?.let {
        out += FactRow("Studio", it.take(3).joinToString(", "))
    }
    networks.takeIf { it.isNotEmpty() }?.let {
        out += FactRow("Network", it.take(3).joinToString(", "))
    }
    countries.takeIf { it.isNotEmpty() }?.let {
        out += FactRow("Country", it.take(3).joinToString(", "))
    }
    // Full genre list (the hero eyebrow only shows the first two); mirrors the
    // phone's "Genres" details row.
    genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
        out += FactRow("Genres", it.joinToString(", "))
    }
    if (type.equals("episode", ignoreCase = true)) {
        TvDetailMetadata.abbreviatedDate(airDate ?: releaseDate)?.let { out += FactRow("Aired", it) }
    } else {
        formattedDate(releaseDate)?.let { out += FactRow("Released", it) }
        formattedDate(firstAirDate)?.let { out += FactRow("First Aired", it) }
        formattedDate(lastAirDate)?.let { out += FactRow("Last Aired", it) }
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

private fun formattedDate(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = trimmed.split("-")
    if (parts.size < 3) return trimmed
    val year = parts[0].toIntOrNull() ?: return trimmed
    val month = parts[1].toIntOrNull() ?: return trimmed
    val day = parts[2].take(2).toIntOrNull() ?: return trimmed
    val monthNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    val monthName = monthNames.getOrNull(month - 1) ?: return trimmed
    return "$monthName $day, $year"
}
