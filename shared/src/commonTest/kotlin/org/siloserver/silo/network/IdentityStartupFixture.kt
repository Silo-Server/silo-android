package org.siloserver.silo.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Begins a real paused mutation immediately after the first guarded startup returns. */
internal class IdentityStartupFixture(
    private val scope: CoroutineScope,
    private val barrier: DefaultIdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) : IdentityTransitionBarrier by barrier {
    val release = CompletableDeferred<Unit>()
    lateinit var replacing: Job
    var mutating = false
        private set
    private var firstReadiness = true

    override suspend fun <T : Any> withCurrentGeneration(expectedGeneration: Long, block: suspend () -> T): T? {
        val result = barrier.withCurrentGeneration(expectedGeneration, block)
        if (firstReadiness) {
            firstReadiness = false
            replacing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) {
                    mutating = true
                    try { release.await() } finally { mutating = false }
                }
            }
        }
        return result
    }
}
