package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail

/** Which part of the Cast & Crew row a card belongs to. */
enum class CastCrewGroup { Lead, Writers, Cast }

/**
 * One card in the detail page's Cast & Crew row. Phone and TV render every
 * credit with the same card, so crew and cast stay the same size.
 */
data class CastCrewCredit(
    val group: CastCrewGroup,
    val name: String,
    /** Caption under the name: the role for crew, the character for cast. */
    val caption: String?,
    val personId: String?,
    val photoUrl: String?,
    val photoThumbhash: String?,
    /**
     * Label for the thin divider drawn before this card, or null when no
     * divider belongs here. Only the first card of a group after the first
     * shown group carries one.
     */
    val dividerLabel: String? = null,
)

internal const val CAST_CREW_MAX_LEADS = 2
internal const val CAST_CREW_MAX_WRITERS = 3
internal const val CAST_CREW_MAX_CAST = 12

/**
 * Builds the Cast & Crew row for a detail page, matching the Silo web layout:
 * up to two directors (captioned "Creator" on series, since Silo stores series
 * creators as Director credits), then up to three writers, then up to twelve
 * cast members by billing order.
 *
 * Only movies and series get the grouped row. Any other type (episodes in
 * particular) keeps the plain cast row exactly as the server sent it.
 */
fun castCrewCredits(detail: ItemDetail): List<CastCrewCredit> {
    val type = detail.type.trim().lowercase()
    return when (type) {
        "movie" -> castCrewCredits(detail.cast, detail.crew, isSeries = false)
        "series" -> castCrewCredits(detail.cast, detail.crew, isSeries = true)
        else -> detail.cast.map { it.toCredit(dividerLabel = null) }
    }
}

fun castCrewCredits(
    cast: List<CastMember>,
    crew: List<CrewMember>,
    isSeries: Boolean,
): List<CastCrewCredit> {
    val directors = crew
        .filter { it.hasJob("Director") }
        .distinctCrew()
    // Exclude every director from the writers, not just the ones shown, so a
    // writer-director past the lead cap doesn't reappear as a writer.
    val leadKeys = directors.mapTo(HashSet()) { it.creditKey() }
    val leads = directors.take(CAST_CREW_MAX_LEADS)
    val writers = crew
        .filter { it.hasJob("Writer") || it.hasJob("Screenplay") }
        .distinctCrew()
        .filterNot { it.creditKey() in leadKeys }
        .take(CAST_CREW_MAX_WRITERS)
    // sortedBy is stable, so equal billing orders keep the server's order.
    val billedCast = cast
        .filter { it.name.isNotBlank() }
        .sortedBy { it.order }
        .take(CAST_CREW_MAX_CAST)

    val leadCaption = if (isSeries) "Creator" else "Director"
    val out = ArrayList<CastCrewCredit>(leads.size + writers.size + billedCast.size)
    leads.forEach { out += it.toCredit(CastCrewGroup.Lead, leadCaption, dividerLabel = null) }
    writers.forEachIndexed { index, member ->
        out += member.toCredit(
            CastCrewGroup.Writers,
            caption = "Writer",
            dividerLabel = "Writers".takeIf { index == 0 && out.isNotEmpty() },
        )
    }
    billedCast.forEachIndexed { index, member ->
        out += member.toCredit(dividerLabel = "Cast".takeIf { index == 0 && out.isNotEmpty() })
    }
    return out
}

/** Up to two initials for a headshot placeholder, e.g. "Greta Gerwig" -> "GG". */
fun personInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

private fun CrewMember.hasJob(job: String): Boolean =
    this.job?.trim().equals(job, ignoreCase = true)

private fun CrewMember.creditKey(): String =
    personId?.trim()?.takeIf { it.isNotEmpty() } ?: "name:${name.trim().lowercase()}"

private fun List<CrewMember>.distinctCrew(): List<CrewMember> =
    filter { it.name.isNotBlank() }.distinctBy { it.creditKey() }

private fun CrewMember.toCredit(
    group: CastCrewGroup,
    caption: String,
    dividerLabel: String?,
) = CastCrewCredit(
    group = group,
    name = name.trim(),
    caption = caption,
    personId = personId,
    photoUrl = photoUrl,
    photoThumbhash = photoThumbhash,
    dividerLabel = dividerLabel,
)

private fun CastMember.toCredit(dividerLabel: String?) = CastCrewCredit(
    group = CastCrewGroup.Cast,
    name = name,
    caption = character,
    personId = personId,
    photoUrl = photoUrl,
    photoThumbhash = photoThumbhash,
    dividerLabel = dividerLabel,
)
