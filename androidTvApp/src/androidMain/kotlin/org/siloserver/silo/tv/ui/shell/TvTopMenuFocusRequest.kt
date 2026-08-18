package org.siloserver.silo.tv.ui.shell

private const val TopMenuFocusMaxAttempts = 6

internal fun isTopMenuFocusTargetAvailable(
    target: TvTopMenuPanel?,
    destinations: List<TvRootDestination>,
): Boolean = when (target) {
    is TvTopMenuPanel.Root -> target.dest in destinations
    TvTopMenuPanel.Profile, null -> true
}

/** Returns only a destination whose focus requester is attached to the current bar. */
internal fun selectedTopMenuEntry(
    selectedRoot: TvRootDestination?,
    destinations: List<TvRootDestination>,
): TvRootDestination? = selectedRoot?.takeIf { it in destinations }

internal enum class TvTopMenuEntryFallback {
    HOME,
    SEARCH,
}

/**
 * Preserves Home as the normal detail-route entry while using the always-composed
 * Search control when an authored root disappeared or Home itself is hidden.
 */
internal fun selectedTopMenuEntryFallback(
    selectedRoot: TvRootDestination?,
    isSearchActive: Boolean,
    destinations: List<TvRootDestination>,
): TvTopMenuEntryFallback = if (
    isSearchActive || selectedRoot != null || TvRootDestination.Home !in destinations
) {
    TvTopMenuEntryFallback.SEARCH
} else {
    TvTopMenuEntryFallback.HOME
}

internal suspend fun handleTopMenuFocusRequestIfAvailable(
    requestIdentity: Pair<Int, TvTopMenuPanel?>,
    lastHandledRequest: Pair<Int, TvTopMenuPanel?>,
    isFocusSuppressed: Boolean,
    isTargetAvailable: Boolean,
    requestFocus: suspend () -> Boolean,
): Pair<Int, TvTopMenuPanel?> {
    if (isFocusSuppressed || !isTargetAvailable || requestIdentity == lastHandledRequest) {
        return lastHandledRequest
    }
    return if (requestFocus()) requestIdentity else lastHandledRequest
}

internal suspend fun requestTopMenuFocusUntilApplied(
    awaitFrame: suspend () -> Unit,
    isTargetCurrent: () -> Boolean = { true },
    requestFocus: () -> Boolean,
): Boolean {
    repeat(TopMenuFocusMaxAttempts) {
        if (!isTargetCurrent()) return false
        awaitFrame()
        if (!isTargetCurrent()) return false
        if (requestFocus()) return true
    }
    return false
}
