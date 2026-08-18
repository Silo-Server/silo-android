package org.siloserver.silo.tv.ui.components

internal enum class TvSkylineUpAction {
    EnterMenu,
    StayInContent,
    TryPreviousRow,
}

/**
 * The row an Up press should be measured from. [focusedRow] is what the last
 * card focus callback reported; it can lag or be clamped (a row-list refresh
 * mid-browse, a focus event not yet delivered on a slow device). The band's
 * scroll position always tracks the focused row, so when the two disagree
 * and the band is scrolled below the top, trust the band: focus cannot be on
 * row 0 while the band shows a lower row at its top.
 */
internal fun tvSkylineEffectiveRow(focusedRow: Int, bandTopRow: Int, rowCount: Int): Int {
    val focusedValid = focusedRow in 0 until rowCount
    return when {
        !focusedValid -> bandTopRow.coerceIn(-1, rowCount - 1)
        focusedRow == 0 && bandTopRow > 0 -> bandTopRow.coerceAtMost(rowCount - 1)
        else -> focusedRow
    }
}

internal fun tvSkylineUpAction(
    currentRow: Int,
    rowCount: Int,
    isRepeat: Boolean,
    relocationInFlight: Boolean,
    bandTopRow: Int = 0,
): TvSkylineUpAction {
    val effectiveRow = tvSkylineEffectiveRow(currentRow, bandTopRow, rowCount)
    return when {
        relocationInFlight -> TvSkylineUpAction.StayInContent
        effectiveRow !in 0 until rowCount ->
            if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
        // Only leave content when the band really is at its top row: a stale
        // "row 0" while the band is scrolled down is a fast double-Up on a slow
        // device, and must step to the previous row instead of jumping to the
        // menu.
        effectiveRow == 0 && bandTopRow <= 0 ->
            if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
        else -> TvSkylineUpAction.TryPreviousRow
    }
}
