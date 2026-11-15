package org.siloserver.silo.tv.ui.screens.home

import org.siloserver.silo.model.section.ResolvedSection
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

private const val CONTINUE_LISTENING_TYPE = "continue_listening"
private const val CONTINUE_LISTENING_ID_SUFFIX = "-continue-listening"

/**
 * The server section a Home row's full page reads. A Continue Listening row
 * made by [splitAudioProgress] keeps its own id, because the TV stores Home
 * row order and visibility by id, but the server only knows the row it came from.
 */
internal fun ResolvedSection.tvHomeBrowseSectionId(): String =
    if (sectionType == CONTINUE_LISTENING_TYPE) id.removeSuffix(CONTINUE_LISTENING_ID_SUFFIX) else id

private fun ResolvedSection.splitAudioProgress(): List<ResolvedSection> {
    val audioItems = items.filter { isAudiobookMediaType(it.type) }
    if (audioItems.isEmpty()) return listOf(this)

    val nonAudioItems = items.filterNot { isAudiobookMediaType(it.type) }
    val listening = copy(
        id = "$id$CONTINUE_LISTENING_ID_SUFFIX",
        sectionType = CONTINUE_LISTENING_TYPE,
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
                    totalCount = nonAudioItems.size,
                    items = nonAudioItems,
                )
            )
        }
        add(listening)
    }
}
