package org.siloserver.silo.tv.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

private const val TvContentInitialFocusRetryDelayMillis = 60L

internal const val TvContentInitialFocusMaxAttempts =
    (TvFocusAcquisitionBudgetMillis / TvContentInitialFocusRetryDelayMillis).toInt()

/**
 * Bounded retry-until-observed initial focus for a screen whose content arrives
 * asynchronously.
 *
 * Separate from the dialog adapter because the failure it prevents is
 * different. A dialog is on screen the instant its effect runs; a content
 * screen composes its first row *during* lazy placement, so the first request
 * is routinely rejected. Screens that treated "attempted" as "acquired"
 * latched that rejection permanently and never focused anything.
 */
internal suspend fun requestTvContentInitialFocus(
    awaitAttempt: suspend () -> Unit,
    isContentFocused: () -> Boolean,
    requestFocus: () -> Boolean,
): TvObservedFocusResult = requestFocusUntilObserved(
    maxAttempts = TvContentInitialFocusMaxAttempts,
    awaitAttempt = awaitAttempt,
    requestFocus = requestFocus,
    isFocused = isContentFocused,
)

/**
 * Whether an anchoring pass should run at all.
 *
 * Nothing to anchor to yet when there is no content. Nothing to do either when
 * the content root already holds focus — the viewer is in there and a refresh
 * must not yank them back to the first item. Checked before the first attempt
 * rather than inside the retry loop, which delays a frame before looking.
 */
internal fun shouldRequestTvContentInitialFocus(
    contentKey: Any?,
    contentHasFocus: Boolean,
): Boolean = contentKey != null && !contentHasFocus

/**
 * Attach the returned modifier to the content root, and [target] to the first
 * item. Anchoring re-runs whenever [contentKey] — the stable identity of the
 * first item — changes, which is what allows a request rejected during lazy
 * placement to be retried instead of latched as failure.
 *
 * "Acquired" here means the content root owns focus, not that [target]
 * specifically does: the observation is `hasFocus` on an ancestor, so focus
 * landing on any descendant satisfies it. That is the property worth having —
 * the failure being prevented is a dead D-pad, and any focused item inside the
 * content prevents it.
 *
 * Refresh does not steal focus, subject to one bound: focus is checked before
 * the first request, so a viewer already inside the content is left alone. If
 * they leave the content *during* an anchoring pass the request can still land
 * and pull them back.
 *
 * [onAcquired] fires only on observed acquisition. Shells use it to hand over
 * content focus, and telling a shell that focus landed when it did not is how
 * a screen ends up with no focus owner at all.
 */
@Composable
internal fun rememberTvContentInitialFocus(
    target: FocusRequester,
    contentKey: Any?,
    onAcquired: () -> Unit = {},
): Modifier {
    var contentHasFocus by remember { mutableStateOf(false) }

    // Deliberately no "already acquired" latch, which is a real trade rather
    // than a free win. With one, A -> null -> A and A -> B-exhausted -> A
    // suppress the request while nothing holds focus — the permanent no-focus
    // state this adapter exists to remove. Without one, those same re-entries
    // will anchor even when focus legitimately sits outside the content root,
    // e.g. in a confirmation dialog, and pull it back.
    //
    // Dead D-pad is the worse failure, so it loses. Resolving it properly needs
    // the modal focus-ownership contract (Series C): a modal that owns focus
    // should suppress content anchoring underneath it, which is knowledge this
    // adapter cannot have on its own.
    LaunchedEffect(target, contentKey) {
        if (!shouldRequestTvContentInitialFocus(contentKey, contentHasFocus)) {
            TvFocusLog.d {
                "contentInitialFocus: skipped (key=$contentKey, alreadyFocused=$contentHasFocus)"
            }
            return@LaunchedEffect
        }
        TvFocusLog.d { "contentInitialFocus: claiming (key=$contentKey)" }
        val result = requestTvContentInitialFocus(
            awaitAttempt = { delay(TvContentInitialFocusRetryDelayMillis) },
            isContentFocused = { contentHasFocus },
            requestFocus = target::requestFocus,
        )
        TvFocusLog.d { "contentInitialFocus: result=$result (key=$contentKey)" }
        if (result == TvObservedFocusResult.Focused) onAcquired()
    }

    return Modifier.onFocusChanged { contentHasFocus = it.hasFocus }
}
