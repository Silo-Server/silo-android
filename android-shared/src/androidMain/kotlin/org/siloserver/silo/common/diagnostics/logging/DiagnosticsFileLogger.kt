package org.siloserver.silo.common.diagnostics.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-mode verbose log sink: append-only newline-framed JSONL segment files
 * (5 × 2 MB) under the diagnostics directory, written by a single consumer
 * coroutine draining a bounded channel. Producers never block — under
 * backpressure new lines are dropped and counted. Segments are compressed only
 * at bundle-build time, so a process death corrupts at most the tail of the
 * active segment.
 *
 * Only runs while the debug-logging device setting is on; the coordinator
 * installs/starts and stops it.
 */
class DiagnosticsFileLogger(baseDir: File) {

    private val dir = File(baseDir, "logs")
    private val channel = Channel<ByteArray>(capacity = 512)
    private val dropped = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false
    private var job: Job? = null
    private var stream: FileOutputStream? = null
    private var activeFile: File? = null
    private var activeSize: Long = 0

    fun start() {
        if (running) return
        running = true
        job = scope.launch { writerLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        closeStreamQuietly()
    }

    /** Non-blocking. No-op while stopped (debug logging off). */
    fun offer(lineUtf8: ByteArray) {
        if (!running) return
        if (channel.trySend(lineUtf8).isFailure) dropped.incrementAndGet()
    }

    fun droppedLines(): Long = dropped.get()

    /** Current segments, oldest first. */
    fun segmentFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith(SEGMENT_PREFIX) && f.name.endsWith(SEGMENT_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun purge() {
        closeStreamQuietly()
        segmentFiles().forEach { it.delete() }
    }

    private suspend fun writerLoop() {
        for (line in channel) {
            if (!running) continue
            runCatching { writeLine(line) }
                .onFailure { Log.w(TAG, "segment write failed", it) }
        }
    }

    private fun writeLine(line: ByteArray) {
        val out = openStreamIfNeeded() ?: return
        out.write(line)
        out.write('\n'.code)
        out.flush()
        activeSize += line.size + 1
        if (activeSize >= MAX_SEGMENT_BYTES) rotate()
    }

    private fun openStreamIfNeeded(): FileOutputStream? {
        stream?.let { return it }
        dir.mkdirs()
        // Resume the newest under-sized segment across runs, else start fresh.
        val newest = segmentFiles().lastOrNull()?.takeIf { it.length() < MAX_SEGMENT_BYTES }
        val target = newest ?: newSegmentFile()
        return runCatching {
            FileOutputStream(target, true).also {
                stream = it
                activeFile = target
                activeSize = target.length()
            }
        }.onFailure { Log.w(TAG, "segment open failed", it) }.getOrNull()
    }

    private fun rotate() {
        closeStreamQuietly()
        val segments = segmentFiles()
        if (segments.size >= MAX_SEGMENTS) {
            segments.take(segments.size - MAX_SEGMENTS + 1).forEach { it.delete() }
        }
    }

    private fun newSegmentFile(): File {
        val nextIndex = segmentFiles()
            .mapNotNull { it.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toLongOrNull() }
            .maxOrNull()?.plus(1) ?: 0L
        return File(dir, "$SEGMENT_PREFIX${nextIndex.toString().padStart(6, '0')}$SEGMENT_SUFFIX")
    }

    private fun closeStreamQuietly() {
        runCatching { stream?.close() }
        stream = null
        activeFile = null
        activeSize = 0
    }

    private companion object {
        const val TAG = "DiagFileLogger"
        const val SEGMENT_PREFIX = "seg-"
        const val SEGMENT_SUFFIX = ".jsonl"
        const val MAX_SEGMENT_BYTES = 2L * 1024 * 1024
        const val MAX_SEGMENTS = 5
    }
}
