package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.Job

/**
 * Holds track and quality picks made while a network quality move is in
 * flight and applies them, in order, once it has finished. Staged during the
 * move, a pick would race it: the move's session could replace the one the
 * pick was staged on, or the move's adoption would reset the transaction and
 * drop the pick. A move that is cancelled (the content changed) drops them.
 *
 * Main thread only, like the player state it guards.
 */
internal class PicksDuringNetworkMove {
    private var move: Job? = null
    private val held = mutableListOf<() -> Unit>()

    /** Holds picks until [move] completes. */
    fun holdUntilDone(move: Job) {
        this.move = move
        move.invokeOnCompletion { cause ->
            if (this.move !== move) return@invokeOnCompletion
            this.move = null
            val picks = held.toList()
            held.clear()
            if (cause == null) picks.forEach { it() }
        }
    }

    /** Runs [pick] now, or after the move in flight. */
    fun applyAfterMove(pick: () -> Unit) {
        if (move?.isActive == true) held += pick else pick()
    }

    fun clear() {
        move = null
        held.clear()
    }
}
