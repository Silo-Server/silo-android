package org.siloserver.silo.tv.ui.shell

private const val TopMenuFocusMaxAttempts = 6

internal fun isTopMenuFocusTargetAvailable(
    target: TvTopMenuPanel?,
    destinations: List<TvRootDestination>,
): Boolean = when (target) {
    is TvTopMenuPanel.Root -> target.dest in destinations
    TvTopMenuPanel.Profile, null -> true
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
