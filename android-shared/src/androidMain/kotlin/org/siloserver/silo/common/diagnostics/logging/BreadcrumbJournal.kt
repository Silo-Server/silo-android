package org.siloserver.silo.common.diagnostics.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Small persistent pre-crash context journal (two rotating 128 KiB segments).
 *
 * The in-memory ring dies with the process, but ANRs and native crashes are
 * only discovered on the *next* launch via ApplicationExitInfo — this journal
 * is what still carries lifecycle/playback/screen context for those reports.
 * Lines are the same rendered JSONL the ring holds, appended crash-safely
 * (flush per line; a process death loses at most the unflushed tail).
 *
 * Runs only while crash reporting for the active binding isn't Never; the
 * coordinator toggles [setEnabled] and purges on Never/sign-out.
 */
class BreadcrumbJournal(baseDir: File) {

    private val dir = File(baseDir, "breadcrumbs")
    private val channel = Channel<ByteArray>(capacity = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var enabled = false
    private var stream: FileOutputStream? = null
    private var activeIndex = 0
    private var activeSize = 0L

    init {
        scope.launch { writerLoop() }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            // Writer closes lazily; nothing pending matters once disabled.
            channel.trySend(CLOSE_SENTINEL)
        }
    }

    /** Non-blocking; drops under backpressure (breadcrumbs are best-effort). */
    fun offer(lineUtf8: ByteArray) {
        if (!enabled) return
        channel.trySend(lineUtf8)
    }

    /** All retained lines, oldest segment first. */
    fun readAllLines(): List<ByteArray> {
        val files = listOf(segmentFile(0), segmentFile(1))
            .filter { it.isFile && it.length() > 0 }
            .sortedBy { it.lastModified() }
        val out = ArrayList<ByteArray>()
        for (file in files) {
            runCatching {
                file.readText(Charsets.UTF_8).lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { out.add(it.encodeToByteArray()) }
            }.onFailure { Log.w(TAG, "breadcrumb read failed", it) }
        }
        return out
    }

    fun purge() {
        closeStreamQuietly()
        segmentFile(0).delete()
        segmentFile(1).delete()
    }

    private suspend fun writerLoop() {
        for (line in channel) {
            if (line === CLOSE_SENTINEL) {
                closeStreamQuietly()
                continue
            }
            if (!enabled) continue
            runCatching { writeLine(line) }
                .onFailure { Log.w(TAG, "breadcrumb write failed", it) }
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
        val zero = segmentFile(0)
        val one = segmentFile(1)
        // Resume the most recently written segment when it still has room.
        activeIndex = when {
            !one.isFile -> 0
            !zero.isFile -> 1
            zero.lastModified() >= one.lastModified() -> 0
            else -> 1
        }
        var target = segmentFile(activeIndex)
        if (target.length() >= MAX_SEGMENT_BYTES) {
            activeIndex = 1 - activeIndex
            target = segmentFile(activeIndex)
            target.delete()
        }
        return runCatching {
            FileOutputStream(target, true).also {
                stream = it
                activeSize = target.length()
            }
        }.onFailure { Log.w(TAG, "breadcrumb open failed", it) }.getOrNull()
    }

    private fun rotate() {
        closeStreamQuietly()
        activeIndex = 1 - activeIndex
        segmentFile(activeIndex).delete()
    }

    private fun segmentFile(index: Int): File = File(dir, "crumb-$index.jsonl")

    private fun closeStreamQuietly() {
        runCatching { stream?.close() }
        stream = null
        activeSize = 0
    }

    private companion object {
        const val TAG = "BreadcrumbJournal"
        const val MAX_SEGMENT_BYTES = 128L * 1024
        val CLOSE_SENTINEL = ByteArray(0)
    }
}
