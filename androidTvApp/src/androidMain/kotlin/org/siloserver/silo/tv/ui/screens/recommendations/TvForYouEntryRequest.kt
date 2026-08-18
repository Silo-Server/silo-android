package org.siloserver.silo.tv.ui.screens.recommendations

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

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

/** Saver for the shell's entry-request slot; see the shell for why it is saved. */
val TvForYouEntryRequestSaver: Saver<TvForYouEntryRequest, Any> = listSaver(
    save = { listOf(it.sequence, it.selection?.name ?: "") },
    restore = { saved ->
        TvForYouEntryRequest(
            sequence = saved[0] as Int,
            selection = (saved[1] as String).takeIf { it.isNotEmpty() }
                ?.let { SavedListSelection.valueOf(it) },
        )
    },
)

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
