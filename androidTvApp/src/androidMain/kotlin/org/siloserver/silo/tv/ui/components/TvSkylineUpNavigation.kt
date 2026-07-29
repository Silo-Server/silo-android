package org.siloserver.silo.tv.ui.components

internal enum class TvSkylineUpAction {
    EnterMenu,
    StayInContent,
    TryPreviousRow,
}

internal fun tvSkylineUpAction(
    currentRow: Int,
    rowCount: Int,
    isRepeat: Boolean,
    relocationInFlight: Boolean,
): TvSkylineUpAction = when {
    relocationInFlight -> TvSkylineUpAction.StayInContent
    currentRow !in 0 until rowCount ->
        if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
    currentRow == 0 ->
        if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
    else -> TvSkylineUpAction.TryPreviousRow
}
