package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player's buffering notice: a display-only line that stays on screen for
 * [shownMs] in all. It waits while [slotFree] is false (the player is hidden,
 * or a notice that outranks it holds the slot) and then shows for the time it
 * has left. A newer notice replaces it.
 */
class BufferingQualityNoticeSlot(
    private val scope: CoroutineScope,
    private val slotFree: Flow<Boolean>,
    private val clock: () -> Long,
    private val shownMs: Long = NOTICE_MS,
) {
    private val _notice = MutableStateFlow<BufferingQualityNotice?>(null)

    /** The notice the player shows now, or null. */
    val notice: StateFlow<BufferingQualityNotice?> = _notice.asStateFlow()

    private var job: Job? = null

    fun show(notice: BufferingQualityNotice) {
        job?.cancel()
        _notice.value = null
        job = scope.launch {
            var remaining = shownMs
            while (remaining > 0) {
                slotFree.first { it }
                _notice.value = notice
                val shownAt = clock()
                val covered = withTimeoutOrNull(remaining) { slotFree.first { !it } }
                remaining -= clock() - shownAt
                _notice.value = null
                if (covered == null) break
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        _notice.value = null
    }

    companion object {
        /** How long a notice shows in all. */
        const val NOTICE_MS: Long = 5_000L
    }
}
