package org.siloserver.silo.domain.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Passes `true` straight through, but only reports `false` once it has held for
 * [graceMillis].
 *
 * `isPlaying` dips false for a rebuffer exactly as it does for a deliberate
 * pause, and consumers that treat the two alike misbehave on a stuttering
 * stream. The intro countdown is the case in hand: a real pause is meant to
 * restart it, so an unfiltered dip hands a stuttering stream a fresh countdown
 * every time it hiccups — and the prompt can sit there indefinitely without
 * ever firing.
 *
 * Asymmetric on purpose. Resuming is not worth delaying: the viewer can see
 * playback running, and holding the countdown back for another second after it
 * does looks like a bug. Only the pause edge is in doubt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.settlingFalseEdges(graceMillis: Long): Flow<Boolean> =
    distinctUntilChanged()
        .flatMapLatest { active ->
            if (active) {
                flowOf(true)
            } else {
                // flatMapLatest cancels this if the value flips back inside the
                // window, which is what swallows a short stall.
                flow {
                    delay(graceMillis)
                    emit(false)
                }
            }
        }
        .distinctUntilChanged()

/**
 * [settlingFalseEdges] for a signal that is ambiguous, plus [deliberatelyInactive]
 * for one that is not.
 *
 * The grace window exists because a dip in the ambiguous signal might be a
 * stall. A viewer pressing pause is not in doubt, and waiting the window out
 * before reporting it leaves a countdown visibly running under a paused
 * picture. So that edge reports at once and only the ambiguous one settles.
 */
fun Flow<Boolean>.settlingFalseEdges(
    graceMillis: Long,
    deliberatelyInactive: Flow<Boolean>,
): Flow<Boolean> =
    combine(
        settlingFalseEdges(graceMillis),
        deliberatelyInactive.distinctUntilChanged(),
    ) { settled, stopped -> settled && !stopped }
        .distinctUntilChanged()
