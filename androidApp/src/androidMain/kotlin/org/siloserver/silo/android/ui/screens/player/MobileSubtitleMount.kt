package org.siloserver.silo.android.ui.screens.player

/** Both transport changes and subtitle refreshes replace Media3 track groups. */
internal data class MobileSubtitleMount(val generation: Long, val refreshNonce: Int)

internal fun isCurrentMobileSubtitleMount(
    applied: MobileSubtitleMount?,
    expected: MobileSubtitleMount,
    awaitingGeneration: Long?,
    loading: Boolean,
): Boolean = applied == expected && awaitingGeneration == null && !loading
