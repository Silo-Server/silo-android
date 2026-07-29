package org.siloserver.silo.tv.ui.screens.recommendations

enum class SavedListSelection {
    Watchlist,
    Favorites,
}

data class TvForYouEntryRequest(
    val sequence: Int = 0,
    val selection: SavedListSelection? = null,
) {
    fun next(selection: SavedListSelection?): TvForYouEntryRequest =
        TvForYouEntryRequest(sequence = sequence + 1, selection = selection)

    fun nextForTopLevelForYou(): TvForYouEntryRequest = next(null)
}

internal data class AppliedForYouSelection(
    val selection: SavedListSelection?,
    val lastAppliedSequence: Int,
    val appliedRequest: Boolean,
)

internal fun applyForYouEntryRequest(
    currentSelection: SavedListSelection?,
    lastAppliedSequence: Int,
    request: TvForYouEntryRequest,
): AppliedForYouSelection =
    if (request.sequence <= lastAppliedSequence) {
        AppliedForYouSelection(
            selection = currentSelection,
            lastAppliedSequence = lastAppliedSequence,
            appliedRequest = false,
        )
    } else {
        AppliedForYouSelection(
            selection = request.selection,
            lastAppliedSequence = request.sequence,
            appliedRequest = true,
        )
    }

internal suspend fun requestForYouEntryFocus(
    selection: SavedListSelection?,
    awaitFrame: suspend () -> Unit,
    requestForYou: () -> Boolean,
    requestWatchlist: () -> Boolean,
    requestFavorites: () -> Boolean,
): Boolean {
    awaitFrame()
    return when (selection) {
        null -> requestForYou()
        SavedListSelection.Watchlist -> requestWatchlist()
        SavedListSelection.Favorites -> requestFavorites()
    }
}
