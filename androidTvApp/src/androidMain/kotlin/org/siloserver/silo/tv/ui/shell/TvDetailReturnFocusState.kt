package org.siloserver.silo.tv.ui.shell

/**
 * Per-root detail-return focus bookkeeping.
 *
 * Root-agnostic: Home and For You both render the Skyline feed, which arms its
 * launch-card requester at click time, so both roots want exactly this ladder.
 */
internal data class TvDetailReturnFocusState(
    val requestId: Int = 0,
    val needsRetry: Boolean = false,
    val fallbackPending: Boolean = false,
)

internal fun beginTvDetailReturnRetry(
    previousRequestId: Int,
    needsRetry: Boolean,
): TvDetailReturnFocusState = TvDetailReturnFocusState(
    requestId = previousRequestId + 1,
    needsRetry = needsRetry,
    fallbackPending = needsRetry,
)

internal fun beginTvDetailReturnRetryIfRoot(
    previousState: TvDetailReturnFocusState,
    isDetailReturnForRoot: Boolean,
    needsRetry: Boolean,
): TvDetailReturnFocusState = if (isDetailReturnForRoot) {
    beginTvDetailReturnRetry(
        previousRequestId = previousState.requestId,
        needsRetry = needsRetry,
    )
} else {
    previousState
}

internal fun completeTvDetailReturnRetry(
    state: TvDetailReturnFocusState,
): TvDetailReturnFocusState = state.copy(
    needsRetry = false,
    fallbackPending = false,
)

internal fun resetTvDetailReturnFocus(): TvDetailReturnFocusState =
    TvDetailReturnFocusState()
