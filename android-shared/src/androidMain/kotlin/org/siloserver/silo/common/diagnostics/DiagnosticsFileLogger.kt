package org.siloserver.silo.common.diagnostics

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

data class FrozenDiagnosticsLogs(
    val generation: Long,
    val files: List<File>,
    val droppedCount: Long,
    val bytes: Long,
)

/**
 * Optional persistent capture. Callers must pass Context.noBackupFilesDir; this class owns only
 * its client-diagnostics/logs subtree.
 */
class DiagnosticsFileLogger(
    noBackupFilesDir: File,
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    private val maxSegmentBytes: Int = DEFAULT_MAX_SEGMENT_BYTES,
    private val deleteRecursively: (File) -> Boolean = File::deleteRecursively,
    private val listFiles: (File) -> Array<File>? = File::listFiles,
    private val directorySync: (File) -> Unit = ::syncDiagnosticsDirectory,
) : DiagnosticsLogSink {
    private val root = noBackupFilesDir.resolve("client-diagnostics/logs")
    private val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private val active = AtomicReference<ActiveCapture?>()

    init {
        require(channelCapacity > 0) { "channelCapacity must be positive" }
        require(maxSegments > 0) { "maxSegments must be positive" }
        require(maxSegmentBytes > 0) { "maxSegmentBytes must be positive" }
        // Construction stays fail-contained so the coordinator can install its identity gate.
        // Its first refresh and every new capture retry this cleanup strictly.
        runCatching { reconcileStoredEvidence() }
    }

    val isActive: Boolean
        get() = active.get() != null

    fun start(generation: Long) {
        require(generation >= 0) { "generation must be non-negative" }
        check(active.get() == null) { "diagnostics file capture is already active" }
        reconcileStoredEvidence()
        val directory = root.resolve("generation-$generation")
        check(directory.mkdirs() || directory.isDirectory) { "unable to create diagnostics log directory" }

        val dropped = AtomicLong(0)
        val channel = Channel<String>(
            capacity = channelCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped.incrementAndGet() },
        )
        val failure = AtomicReference<Throwable?>()
        val writer = scope.launch {
            try {
                SegmentWriter(directory, maxSegments, maxSegmentBytes).use { segments ->
                    for (line in channel) segments.append(line)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val capture = ActiveCapture(generation, directory, channel, writer, dropped, failure)
        if (!active.compareAndSet(null, capture)) {
            channel.close()
            writer.cancel()
            error("diagnostics file capture started concurrently")
        }
    }

    override fun offer(renderedJsonLine: String) {
        val capture = active.get() ?: return
        val lineBytes = renderedJsonLine.encodeToByteArray().size + NEWLINE_BYTES
        if (
            renderedJsonLine.isEmpty() ||
            renderedJsonLine.indexOfAny(charArrayOf('\r', '\n')) >= 0 ||
            lineBytes > maxSegmentBytes
        ) {
            capture.dropped.incrementAndGet()
            return
        }
        if (capture.channel.trySend(renderedJsonLine).isFailure) capture.dropped.incrementAndGet()
    }

    suspend fun freeze(expectedGeneration: Long): FrozenDiagnosticsLogs {
        val capture = detach(expectedGeneration)
        capture.channel.close()
        capture.writer.join()
        capture.failure.get()?.let { error ->
            throw IOException("diagnostics file writer failed", error)
        }
        val files = capture.directory.segmentFiles()
        return FrozenDiagnosticsLogs(
            generation = capture.generation,
            files = files,
            droppedCount = capture.dropped.get(),
            bytes = files.sumOf(File::length),
        )
    }

    suspend fun cancel(expectedGeneration: Long) {
        val capture = detach(expectedGeneration)
        capture.channel.close()
        capture.writer.cancelAndJoin()
        deleteDiagnosticsEvidenceStrictly(capture.directory, deleteRecursively, directorySync)
    }

    suspend fun purgeStoredEvidence() {
        active.get()?.let { capture -> cancel(capture.generation) }
        deleteDiagnosticsEvidenceStrictly(root, deleteRecursively, directorySync)
    }

    /** Strictly removes generations that no active writer owns, including crash leftovers. */
    fun reconcileStoredEvidence() {
        if (!root.exists()) return
        check(root.isDirectory) { "diagnostics log root is not a directory" }
        val activeDirectory = active.get()?.directory?.canonicalFile
        val entries = checkNotNull(listFiles(root)) { "unable to enumerate diagnostics log root" }
        entries.forEach { entry ->
            if (activeDirectory == null || entry.canonicalFile != activeDirectory) {
                deleteDiagnosticsEvidenceStrictly(entry, deleteRecursively, directorySync)
            }
        }
        if (activeDirectory == null) {
            deleteDiagnosticsEvidenceStrictly(root, deleteRecursively, directorySync)
        }
    }

    /** Removes the detached generation after its bounded bytes have been published. */
    fun deleteFrozen(frozen: FrozenDiagnosticsLogs) {
        val directory = root.resolve("generation-${frozen.generation}")
        require(
            frozen.files.all { file -> file.parentFile?.canonicalFile == directory.canonicalFile },
        ) { "frozen diagnostics files do not belong to generation ${frozen.generation}" }
        deleteDiagnosticsEvidenceStrictly(directory, deleteRecursively, directorySync)
    }

    private fun detach(expectedGeneration: Long): ActiveCapture {
        val capture = active.get() ?: error("diagnostics file capture is not active")
        require(capture.generation == expectedGeneration) {
            "diagnostics generation mismatch: expected=$expectedGeneration active=${capture.generation}"
        }
        check(active.compareAndSet(capture, null)) { "diagnostics file capture changed concurrently" }
        return capture
    }

    private data class ActiveCapture(
        val generation: Long,
        val directory: File,
        val channel: Channel<String>,
        val writer: Job,
        val dropped: AtomicLong,
        val failure: AtomicReference<Throwable?>,
    )

    private class SegmentWriter(
        private val directory: File,
        private val maxSegments: Int,
        private val maxSegmentBytes: Int,
    ) : AutoCloseable {
        private var index = directory.segmentFiles()
            .maxOfOrNull { it.nameWithoutExtension.substringAfter("segment-").toIntOrNull() ?: -1 }
            ?.plus(1)
            ?: 0
        private var currentFile: File? = null
        private var output: BufferedOutputStream? = null
        private var currentBytes = 0L

        fun append(line: String) {
            val bytes = line.encodeToByteArray() + byteArrayOf('\n'.code.toByte())
            if (currentFile == null || currentBytes + bytes.size > maxSegmentBytes) rotate()
            output!!.write(bytes)
            output!!.flush()
            currentBytes += bytes.size
        }

        private fun rotate() {
            output?.close()
            val file = directory.resolve("segment-${String.format(Locale.ROOT, "%05d", index++)}.jsonl")
            output = BufferedOutputStream(FileOutputStream(file, true))
            currentFile = file
            currentBytes = file.length()

            val files = directory.segmentFiles()
            files.take((files.size - maxSegments).coerceAtLeast(0)).forEach { stale ->
                check(stale.delete()) { "unable to rotate diagnostics log segment ${stale.name}" }
            }
        }

        override fun close() {
            output?.close()
            output = null
            currentFile = null
        }
    }

    private companion object {
        const val NEWLINE_BYTES = 1
        const val DEFAULT_CHANNEL_CAPACITY = 512
        const val DEFAULT_MAX_SEGMENTS = 5
        const val DEFAULT_MAX_SEGMENT_BYTES = 2 * 1_024 * 1_024

        fun File.segmentFiles(): List<File> =
            listFiles { file -> file.isFile && file.name.matches(Regex("segment-\\d{5}\\.jsonl")) }
                .orEmpty()
                .sortedBy(File::getName)
    }
}
