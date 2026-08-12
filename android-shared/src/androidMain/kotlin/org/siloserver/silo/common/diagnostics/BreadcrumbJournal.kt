package org.siloserver.silo.common.diagnostics

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine

fun interface DiagnosticsBreadcrumbSource {
    fun linesForRun(captureSessionId: String, identityKey: DiagnosticsIdentityKey): List<String>

    data object None : DiagnosticsBreadcrumbSource {
        override fun linesForRun(captureSessionId: String, identityKey: DiagnosticsIdentityKey) = emptyList<String>()
    }
}

/**
 * Bounded persistent context for exits discovered only after process death (ANR/native crash).
 * Only explicit [SiloLog.breadcrumb] events enter this journal; all lines are already rendered
 * and redacted. A generation change closes the gate synchronously and resets files on the writer.
 */
class BreadcrumbJournal(
    noBackupFilesDir: File,
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxSegmentBytes: Int = DEFAULT_MAX_SEGMENT_BYTES,
    private val listFiles: (File) -> Array<File>? = File::listFiles,
    private val deleteRecursively: (File) -> Boolean = File::deleteRecursively,
    private val directorySync: (File) -> Unit = ::syncDiagnosticsDirectory,
) : DiagnosticsLogSink, DiagnosticsBreadcrumbSource {
    private val root = noBackupFilesDir.resolve("client-diagnostics/breadcrumbs")
    private val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private val messages = Channel<Message>(capacity = CHANNEL_CAPACITY)
    private val enabled = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val activeOwner = AtomicReference<String?>(null)

    init {
        require(maxSegmentBytes > 0)
        scope.launch { writerLoop() }
    }

    fun setEnabled(identityKey: DiagnosticsIdentityKey) {
        val owner = identityKey.ownerKey()
        val ownerChanged = activeOwner.getAndSet(owner) != owner
        if (enabled.compareAndSet(false, true) || ownerChanged) rotateGeneration(owner)
    }

    /** Synchronous capture gate; deletion is serialized on the journal writer. */
    fun closeGate() {
        val wasEnabled = enabled.getAndSet(false)
        activeOwner.set(null)
        if (wasEnabled) rotateGeneration(null)
    }

    override fun offer(renderedJsonLine: String) {
        val capturedGeneration = generation.get()
        val capturedOwner = activeOwner.get() ?: return
        if (!enabled.get()) return
        val size = renderedJsonLine.encodeToByteArray().size
        if (
            renderedJsonLine.isBlank() ||
            renderedJsonLine.indexOfAny(charArrayOf('\r', '\n')) >= 0 ||
            size > MAX_LINE_BYTES
        ) {
            return
        }
        messages.trySend(Message.Line(capturedGeneration, capturedOwner, renderedJsonLine))
    }

    override fun linesForRun(
        captureSessionId: String,
        identityKey: DiagnosticsIdentityKey,
    ): List<String> {
        if (captureSessionId.isBlank()) return emptyList()
        var remainingBytes = MAX_RETAINED_BYTES
        return segmentFiles(identityKey.ownerKey())
            .sortedBy(File::lastModified)
            .flatMap { file ->
                runCatching {
                    file.useLines { lines ->
                        lines.mapNotNull { line ->
                            val bytes = line.encodeToByteArray().size
                            if (bytes == 0 || bytes > MAX_LINE_BYTES || bytes > remainingBytes) {
                                null
                            } else {
                                val matches = runCatching {
                                    JSON.decodeFromString<DiagnosticsLogLine>(line).run == captureSessionId
                                }.getOrDefault(false)
                                if (matches) {
                                    remainingBytes -= bytes
                                    line
                                } else {
                                    null
                                }
                            }
                        }.toList()
                    }
                }.onFailure { Log.w(TAG, "breadcrumb read failed", it) }.getOrDefault(emptyList())
            }
    }

    suspend fun purge() {
        enabled.set(false)
        activeOwner.set(null)
        val nextGeneration = generation.incrementAndGet()
        val completion = CompletableDeferred<Unit>()
        messages.send(Message.Reset(nextGeneration, null, completion))
        completion.await()
    }

    private fun rotateGeneration(owner: String?) {
        val nextGeneration = generation.incrementAndGet()
        scope.launch { messages.send(Message.Reset(nextGeneration, owner)) }
    }

    private suspend fun writerLoop() {
        var diskGeneration: Long? = null
        var diskOwner: String? = null
        var activeSegment = 0
        var activeBytes = 0L
        var output: BufferedOutputStream? = null

        fun closeOutput() {
            runCatching { output?.close() }
            output = null
            activeBytes = 0
        }

        fun resetFiles(nextGeneration: Long, nextOwner: String?) {
            closeOutput()
            allEvidenceFilesStrictly().forEach { file ->
                deleteDiagnosticsEvidenceStrictly(file, deleteRecursively, directorySync)
            }
            diskGeneration = nextGeneration
            diskOwner = nextOwner
            activeSegment = 0
        }

        fun openOutput(): BufferedOutputStream {
            output?.let { return it }
            val owner = checkNotNull(diskOwner) { "breadcrumb owner is not active" }
            check(root.mkdirs() || root.isDirectory) { "unable to create breadcrumb directory" }
            val target = segmentFile(owner, activeSegment)
            activeBytes = target.length()
            return BufferedOutputStream(FileOutputStream(target, true)).also { output = it }
        }

        fun append(line: String) {
            val bytes = line.encodeToByteArray()
            if (activeBytes > 0 && activeBytes + bytes.size + 1 > maxSegmentBytes) {
                closeOutput()
                activeSegment = 1 - activeSegment
                val stale = segmentFile(checkNotNull(diskOwner), activeSegment)
                deleteDiagnosticsEvidenceStrictly(stale, deleteRecursively, directorySync)
            }
            openOutput().apply {
                write(bytes)
                write('\n'.code)
                flush()
            }
            activeBytes += bytes.size + 1
        }

        for (message in messages) {
            when (message) {
                is Message.Line -> {
                    if (
                        message.generation != generation.get() ||
                        message.owner != activeOwner.get()
                    ) continue
                    runCatching {
                        if (diskGeneration != message.generation || diskOwner != message.owner) {
                            resetFiles(message.generation, message.owner)
                        }
                        append(message.value)
                    }.onFailure {
                        enabled.set(false)
                        Log.w(TAG, "breadcrumb write failed", it)
                    }
                }
                is Message.Reset -> {
                    try {
                        if (
                            message.generation == generation.get() &&
                            message.owner == activeOwner.get() &&
                            (diskGeneration != message.generation || diskOwner != message.owner)
                        ) {
                            resetFiles(message.generation, message.owner)
                        }
                        message.completion?.complete(Unit)
                    } catch (error: Throwable) {
                        enabled.set(false)
                        message.completion?.completeExceptionally(error)
                        Log.w(TAG, "breadcrumb reset failed", error)
                    }
                }
            }
        }
        closeOutput()
    }

    private fun segmentFile(owner: String, index: Int) = root.resolve("segment-$owner-$index.jsonl")

    private fun segmentFiles(owner: String): List<File> =
        listOf(segmentFile(owner, 0), segmentFile(owner, 1)).filter(File::isFile)

    private fun allEvidenceFilesStrictly(): List<File> {
        if (!root.exists()) return emptyList()
        check(root.isDirectory) { "diagnostics breadcrumb path is not a directory" }
        return checkNotNull(listFiles(root)) { "unable to enumerate diagnostics breadcrumbs" }.toList()
    }

    private fun DiagnosticsIdentityKey.ownerKey(): String {
        val source = listOf(
            binding.serverInstanceId,
            binding.accountUserId,
            profileId.orEmpty(),
            ownershipGeneration.toString(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(source.encodeToByteArray())
            .take(OWNER_HASH_BYTES)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private sealed interface Message {
        data class Line(val generation: Long, val owner: String, val value: String) : Message
        data class Reset(
            val generation: Long,
            val owner: String?,
            val completion: CompletableDeferred<Unit>? = null,
        ) : Message
    }

    private companion object {
        const val TAG = "BreadcrumbJournal"
        const val CHANNEL_CAPACITY = 128
        const val MAX_LINE_BYTES = 8 * 1_024
        const val MAX_RETAINED_BYTES = 256 * 1_024
        const val DEFAULT_MAX_SEGMENT_BYTES = 128 * 1_024
        const val OWNER_HASH_BYTES = 16
        val SEGMENT_PATTERN = Regex("segment-[0-9a-f]{32}-[01]\\.jsonl")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
