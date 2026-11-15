package org.siloserver.silo.tv.ui.screens.home

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.showsWholeList
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.isTvProgressRow
import org.siloserver.silo.tv.ui.util.isAudiobookMediaType
import org.siloserver.silo.tv.ui.util.visibleOnTv

internal fun List<ResolvedSection>.normalizeTvHomeSections(): List<ResolvedSection> =
    visibleOnTv().flatMap { section ->
        if (section.isTvProgressRow()) section.splitAudioProgress() else listOf(section)
    }

internal fun ResolvedSection.isTvAudioProgressSection(): Boolean =
    isTvProgressRow() && items.isNotEmpty() && items.all { isAudiobookMediaType(it.type) }

internal fun ResolvedSection.tvHomeRowStyle(): TvRowStyle = when {
    isTvAudioProgressSection() -> TvRowStyle.Poster
    isTvProgressRow() -> TvRowStyle.Backdrop
    else -> TvRowStyle.Poster
}

private fun ResolvedSection.splitAudioProgress(): List<ResolvedSection> {
    val audioItems = items.filter { isAudiobookMediaType(it.type) }
    if (audioItems.isEmpty()) return listOf(this)

    val nonAudioItems = items.filterNot { isAudiobookMediaType(it.type) }
    val listening = copy(
        id = "$id-continue-listening",
        sectionType = "continue_listening",
        // English fallback; sectionTitle() shows the catalog's text for titleKey.
        // The key must be replaced, or the row would inherit the source row's.
        title = "Continue Listening",
        titleKey = "home.section.continueListening",
        titleArgs = null,
        totalCount = audioItems.size,
        items = audioItems,
    )

    return buildList {
        if (nonAudioItems.isNotEmpty()) {
            add(
                copy(
                    // Only a row that held its whole list counts its own cards.
                    // One cut at its limit keeps the source count, so losing its
                    // audiobooks does not make it look whole and drop "Explore all".
                    totalCount = if (showsWholeList) nonAudioItems.size else totalCount,
                    items = nonAudioItems,
                )
            )
        }
        add(listening)
    }
}
