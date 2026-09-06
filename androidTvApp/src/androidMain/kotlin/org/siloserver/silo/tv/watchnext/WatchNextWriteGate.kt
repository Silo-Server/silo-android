package org.siloserver.silo.tv.watchnext

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders provider writes and wipes; invalidation does not wait for binder I/O. */
internal class WatchNextWriteGate {
    private val generation = AtomicLong()
    private val mutex = Mutex()

    fun capture(): Long = generation.get()
    fun invalidate() { generation.incrementAndGet() }
    fun current(run: Long): Boolean = run == generation.get()

    suspend fun allowed(run: Long, authority: suspend () -> Boolean): Boolean {
        if (!current(run)) return false
        val valid = authority()
        currentCoroutineContext().ensureActive()
        return valid && current(run)
    }

    suspend fun <T> write(run: Long, authority: suspend () -> Boolean, block: suspend () -> T): T? =
        mutex.withLock { if (allowed(run, authority)) block() else null }

    suspend fun clear(block: suspend () -> Unit) = mutex.withLock { block() }
}
