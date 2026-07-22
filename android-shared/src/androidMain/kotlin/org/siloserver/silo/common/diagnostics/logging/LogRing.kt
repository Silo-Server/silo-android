package org.siloserver.silo.common.diagnostics.logging

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Always-on in-memory diagnostics ring.
 *
 * Lock-free by design, not merely low-contention: the crash handler snapshots
 * this ring from a dying thread, so no path here may block on a lock another
 * (live) thread could be holding. Writes are wait-free — an atomic sequence
 * claim plus two array stores; reads validate each slot's sequence after
 * reading it, so a torn slot (overwritten mid-scan) is skipped, never returned
 * half-written. Entries are pre-rendered UTF-8 JSON lines; the ring stores and
 * returns opaque bytes.
 *
 * Slot publication order is data-then-seq, read seq-then-data — the standard
 * safe-publication pairing the JMM guarantees for atomic arrays.
 */
class LogRing(private val capacity: Int = DEFAULT_CAPACITY) {

    private val writeSeq = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val slotData = AtomicReferenceArray<ByteArray?>(capacity)
    private val slotSeq = AtomicLongArray(capacity).apply {
        for (i in 0 until capacity) set(i, -1L)
    }

    /** Non-blocking; safe from any thread, including the UEH dying thread. */
    fun append(renderedUtf8: ByteArray) {
        val seq = writeSeq.getAndIncrement()
        val idx = (seq % capacity).toInt()
        if (seq >= capacity) dropped.incrementAndGet()
        slotData.set(idx, renderedUtf8)
        slotSeq.set(idx, seq)
    }

    /** All retained entries, oldest-first. Bounded O(capacity), lock-free. */
    fun snapshot(): Snapshot = snapshotRange(maxLines = capacity)

    /**
     * Newest [maxLines] entries, oldest-first. Used by the crash handler with
     * a small line budget so a full ring never blows the crash-time budget.
     */
    fun snapshotNewest(maxLines: Int): Snapshot = snapshotRange(maxLines)

    private fun snapshotRange(maxLines: Int): Snapshot {
        val endSeq = writeSeq.get()
        val window = minOf(capacity.toLong(), maxLines.toLong(), endSeq)
        val startSeq = endSeq - window
        val out = ArrayList<ByteArray>(window.toInt())
        var seq = startSeq
        while (seq < endSeq) {
            val idx = (seq % capacity).toInt()
            // Seq first, then data: a slot whose seq doesn't match was either
            // not yet published or already overwritten — skip, never tear.
            if (slotSeq.get(idx) == seq) {
                slotData.get(idx)?.let(out::add)
            }
            seq++
        }
        return Snapshot(lines = out, droppedCount = dropped.get())
    }

    class Snapshot(val lines: List<ByteArray>, val droppedCount: Long)

    companion object {
        const val DEFAULT_CAPACITY = 4000
    }
}
