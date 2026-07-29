package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.section.SectionItem

internal data class TvSkylineSettledFocus(
    val rowIndex: Int,
    val contentId: String,
)

internal fun settledFocusIdentity(
    rawRowIndex: Int,
    rawFocusedContentId: String?,
    rawFocusedMarqueeId: String?,
    settledMarqueeId: String?,
): TvSkylineSettledFocus? {
    if (
        rawRowIndex < 0 ||
        rawFocusedContentId == null ||
        rawFocusedMarqueeId == null ||
        rawFocusedMarqueeId != settledMarqueeId
    ) {
        return null
    }
    return TvSkylineSettledFocus(rawRowIndex, rawFocusedContentId)
}

internal fun settledPrefetchItems(
    items: List<SectionItem>,
    rawFocusedContentId: String?,
    settledContentId: String?,
    radius: Int = 2,
): List<SectionItem> {
    if (rawFocusedContentId == null || rawFocusedContentId != settledContentId || radius <= 0) {
        return emptyList()
    }
    val focusedIndex = items.indexOfFirst { it.contentId == settledContentId }
    if (focusedIndex < 0) return emptyList()

    return ((focusedIndex - radius)..(focusedIndex + radius))
        .filter { it in items.indices && it != focusedIndex }
        .map(items::get)
}
