package org.siloserver.silo.tv.ui.navigation

/**
 * The subtitle decision a Play action carries from the detail screen into the
 * player, in COMBINED selection space (-1 = Off).
 *
 * [autoResolved] is the whole reason this is a type rather than a bare Int. The
 * detail row now hands over its Auto preview too — otherwise the player
 * re-derives Auto over Media3's mounted tracks, where an external sidecar the
 * initial plan never mounted cannot be a candidate, and playback starts on a
 * different track than the row displayed. But an auto-resolved index is NOT a
 * choice the viewer made: it must not be persisted as a durable per-item
 * preference and must not be carried into the next episode as an explicit
 * intent.
 */
data class TvSubtitleLaunchSelection(
    val selectionIndex: Int,
    val autoResolved: Boolean,
) {
    /** The value the viewer explicitly picked, or null when Auto resolved it. */
    val explicitSelectionIndex: Int? get() = selectionIndex.takeIf { !autoResolved }
}

/** A selection the viewer made themselves (null stays "no explicit pick"). */
fun explicitTvSubtitleLaunchSelection(index: Int?): TvSubtitleLaunchSelection? =
    index?.let { TvSubtitleLaunchSelection(it, autoResolved = false) }
