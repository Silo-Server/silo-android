package org.siloserver.silo.common.diagnostics

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class CrashRuntimeSnapshot(
    val identityKey: DiagnosticsIdentityKey? = null,
    val binding: PendingReportBinding? = null,
    val captureSessionId: String? = null,
    val runToken: String? = null,
    val foreground: Boolean? = null,
    val playbackSessionIds: List<String> = emptyList(),
    val deviceSnapshotJson: String? = null,
    /** Live, pre-redacted ring. The crash path snapshots it at failure time. */
    val logBuffer: DiagnosticsLogBuffer? = null,
    val logLines: List<String> = emptyList(),
    val logDroppedCount: Long = 0,
    val logTornCount: Long = 0,
    val logGeneration: Long = 0,
    val redactionTokens: List<String> = emptyList(),
) {
    companion object {
        fun empty() = CrashRuntimeSnapshot()
    }
}

@Serializable
data class JvmCrashMarkerRecord(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("occurred_at_epoch_ms") val occurredAtEpochMs: Long,
    @SerialName("thread_name") val threadName: String,
    @SerialName("thread_id") val threadId: Long,
    @SerialName("throwable_type") val throwableType: String,
    val stack: String,
    val binding: PendingReportBinding? = null,
    @SerialName("capture_session_id") val captureSessionId: String? = null,
    @SerialName("run_token") val runToken: String? = null,
    val foreground: Boolean? = null,
    @SerialName("playback_session_ids") val playbackSessionIds: List<String>,
    @SerialName("device_snapshot_json") val deviceSnapshotJson: String? = null,
    @SerialName("log_lines") val logLines: List<String>,
    @SerialName("log_dropped_count") val logDroppedCount: Long,
    @SerialName("log_torn_count") val logTornCount: Long,
    @SerialName("log_generation") val logGeneration: Long,
    val truncated: Boolean,
    @Transient val sourceFileName: String? = null,
)

fun interface CrashMarkerSink {
    fun write(thread: Thread, throwable: Throwable, runtime: CrashRuntimeSnapshot)
}

internal class JvmCrashMarkerFileGate {
    private val monitor = Any()

    fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

internal val JVM_CRASH_MARKER_FILE_GATE = JvmCrashMarkerFileGate()

internal class CrashExceptionHandler(
    private val markerSink: CrashMarkerSink,
    private val runtimeSnapshot: () -> CrashRuntimeSnapshot,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val writeGate: JvmCrashMarkerFileGate? = null,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val write = { markerSink.write(thread, throwable, runtimeSnapshot()) }
            writeGate?.withLock(write) ?: write()
        } catch (_: Throwable) {
            // The platform/default handler remains authoritative even if evidence capture fails.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}

class CrashMarkerRenderer {
    fun render(
        thread: Thread,
        throwable: Throwable,
        runtime: CrashRuntimeSnapshot,
        occurredAtEpochMs: Long,
        shouldStop: () -> Boolean = { false },
    ): ByteArray {
        val prioritizedRedactionValues = runtime.redactionTokens
            .asSequence()
            .filter(String::isNotEmpty)
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        val redactionOverflow = prioritizedRedactionValues.size > MAX_EXACT_REDACTION_VALUES
        val exactRedactionValues = prioritizedRedactionValues.take(MAX_EXACT_REDACTION_VALUES)
        val logs = runtime.logBuffer?.snapshot(runtime.logGeneration)
            ?: LogSnapshot(
                lines = runtime.logLines,
                droppedCount = runtime.logDroppedCount,
                tornCount = runtime.logTornCount,
                generation = runtime.logGeneration,
            )
        val marker = JvmCrashMarkerRecord(
            occurredAtEpochMs = occurredAtEpochMs.coerceAtLeast(0),
            threadName = if (redactionOverflow) {
                ""
            } else {
                scrubExact(thread.name, exactRedactionValues, shouldStop)
                    .orEmpty()
                    .truncateUtf8(MAX_FIELD_BYTES)
            },
            threadId = thread.stableId(),
            throwableType = throwable.javaClass.name.truncateUtf8(MAX_FIELD_BYTES),
            stack = if (redactionOverflow) {
                throwable.javaClass.name.truncateUtf8(MAX_FIELD_BYTES)
            } else {
                renderThrowable(throwable, exactRedactionValues, MAX_STACK_BYTES, shouldStop)
            },
            binding = runtime.binding?.bounded(),
            captureSessionId = runtime.captureSessionId?.truncateUtf8(MAX_FIELD_BYTES),
            runToken = runtime.runToken?.truncateUtf8(MAX_FIELD_BYTES),
            foreground = runtime.foreground,
            playbackSessionIds = runtime.playbackSessionIds.take(MAX_PLAYBACK_IDS).map {
                it.truncateUtf8(MAX_FIELD_BYTES)
            },
            deviceSnapshotJson = runtime.deviceSnapshotJson?.truncateUtf8(MAX_DEVICE_BYTES),
            logLines = if (redactionOverflow) {
                emptyList()
            } else {
                boundedNewestLogs(
                    lines = logs.lines,
                    maxBytes = MAX_LOG_BYTES,
                    exactRedactionValues = exactRedactionValues,
                    shouldStop = shouldStop,
                )
            },
            logDroppedCount = logs.droppedCount.coerceAtLeast(0),
            logTornCount = logs.tornCount.coerceAtLeast(0),
            logGeneration = logs.generation.coerceAtLeast(0),
            truncated = shouldStop(),
        )
        encode(marker).takeIf { it.size <= MAX_MARKER_BYTES }?.let { return it }

        val reduced = marker.copy(
            stack = marker.stack.truncateUtf8(REDUCED_STACK_BYTES),
            deviceSnapshotJson = marker.deviceSnapshotJson?.truncateUtf8(REDUCED_DEVICE_BYTES),
            logLines = boundedNewestLogs(marker.logLines, REDUCED_LOG_BYTES),
            truncated = true,
        )
        encode(reduced).takeIf { it.size <= MAX_MARKER_BYTES }?.let { return it }

        val minimal = reduced.copy(
            binding = null,
            captureSessionId = null,
            runToken = null,
            playbackSessionIds = emptyList(),
            deviceSnapshotJson = null,
            logLines = emptyList(),
            stack = reduced.stack.truncateUtf8(MINIMAL_STACK_BYTES),
        )
        return encode(minimal).takeIf { it.size <= MAX_MARKER_BYTES } ?: MINIMAL_MARKER
    }

    private fun renderThrowable(
        throwable: Throwable,
        exactRedactionValues: List<String>,
        maxBytes: Int,
        shouldStop: () -> Boolean,
    ): String {
        val output = BoundedUtf8Builder(maxBytes)
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current) && !shouldStop()) {
            val prefix = if (depth == 0) "" else "caused by "
            val message = current.message
                ?.take(MAX_RAW_MESSAGE_CHARS)
                ?.let { scrubExact(it, exactRedactionValues, shouldStop) }
                ?.truncateUtf8(MAX_MESSAGE_BYTES)
            if (!output.append("$prefix${current.javaClass.name}${message?.let { ": $it" }.orEmpty()}\n")) break
            for (frame in current.stackTrace) {
                if (shouldStop()) return output.toString()
                val rendered = "    at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown Source"}:${frame.lineNumber})\n"
                if (!output.append(rendered)) return output.toString()
            }
            current = current.cause
            depth += 1
        }
        return output.toString()
    }

    private fun scrubExact(
        value: String,
        exactRedactionValues: List<String>,
        shouldStop: () -> Boolean,
    ): String? {
        var scrubbed = value
        exactRedactionValues.forEach { sensitiveValue ->
            if (shouldStop()) return null
            scrubbed = scrubbed.replace(sensitiveValue, REDACTED_VALUE)
        }
        return scrubbed
    }

    private fun boundedNewestLogs(
        lines: List<String>,
        maxBytes: Int,
        exactRedactionValues: List<String> = emptyList(),
        shouldStop: () -> Boolean = { false },
    ): List<String> {
        val newestFirst = ArrayList<String>()
        var usedBytes = 0
        for (index in lines.lastIndex downTo 0) {
            if (shouldStop()) break
            val line = scrubExact(lines[index], exactRedactionValues, shouldStop)
                ?.truncateUtf8(MAX_LOG_LINE_BYTES)
                ?: break
            val bytes = line.encodeToByteArray().size
            if (usedBytes + bytes > maxBytes) break
            newestFirst += line
            usedBytes += bytes
        }
        newestFirst.reverse()
        return newestFirst
    }

    /**
     * Manual marker JSON keeps kotlinx serialization and its reflection/allocation graph off the
     * dying thread. Raw exception text stays app-private and is redacted during next-launch
     * assembly before it can enter a pending report.
     */
    private fun encode(marker: JvmCrashMarkerRecord): ByteArray = buildString(8 * 1_024) {
        append('{')
        append("\"schema_version\":").append(marker.schemaVersion)
        append(",\"occurred_at_epoch_ms\":").append(marker.occurredAtEpochMs)
        append(",\"thread_name\":").appendJsonString(marker.threadName)
        append(",\"thread_id\":").append(marker.threadId)
        append(",\"throwable_type\":").appendJsonString(marker.throwableType)
        append(",\"stack\":").appendJsonString(marker.stack)
        append(",\"binding\":")
        marker.binding?.let { binding ->
            append('{')
            append("\"server_instance_id\":").appendJsonString(binding.serverInstanceId)
            append(",\"account_user_id\":").appendJsonString(binding.accountUserId)
            binding.profileId?.let { append(",\"profile_id\":").appendJsonString(it) }
            append(",\"ownership_generation\":").append(binding.ownershipGeneration)
            append(",\"destination_kind\":").appendJsonString(binding.destinationKind.name)
            append('}')
        } ?: append("null")
        marker.captureSessionId?.let { append(",\"capture_session_id\":").appendJsonString(it) }
        marker.runToken?.let { append(",\"run_token\":").appendJsonString(it) }
        marker.foreground?.let { append(",\"foreground\":").append(it) }
        append(",\"playback_session_ids\":[")
        marker.playbackSessionIds.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonString(value)
        }
        append(']')
        marker.deviceSnapshotJson?.let { append(",\"device_snapshot_json\":").appendJsonString(it) }
        append(",\"log_lines\":[")
        marker.logLines.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonString(value)
        }
        append(']')
        append(",\"log_dropped_count\":").append(marker.logDroppedCount)
        append(",\"log_torn_count\":").append(marker.logTornCount)
        append(",\"log_generation\":").append(marker.logGeneration)
        append(",\"truncated\":").append(marker.truncated)
        append('}')
    }.encodeToByteArray()

    companion object {
        const val MAX_MARKER_BYTES = 512 * 1_024
        private const val MAX_STACK_BYTES = 96 * 1_024
        private const val MAX_LOG_BYTES = 256 * 1_024
        private const val MAX_DEVICE_BYTES = 64 * 1_024
        private const val REDUCED_STACK_BYTES = 64 * 1_024
        private const val REDUCED_LOG_BYTES = 128 * 1_024
        private const val REDUCED_DEVICE_BYTES = 32 * 1_024
        private const val MINIMAL_STACK_BYTES = 4 * 1_024
        private const val MAX_LOG_LINE_BYTES = 8 * 1_024
        private const val MAX_MESSAGE_BYTES = 8 * 1_024
        private const val MAX_RAW_MESSAGE_CHARS = 16 * 1_024
        private const val MAX_FIELD_BYTES = 512
        private const val MAX_PLAYBACK_IDS = 20
        private const val MAX_CAUSE_DEPTH = 8
        private const val MAX_EXACT_REDACTION_VALUES = 16
        private const val REDACTED_VALUE = "[REDACTED]"
        private val MINIMAL_MARKER = "{\"schema_version\":1,\"truncated\":true}".encodeToByteArray()
    }
}

class FileCrashMarkerWriter(
    noBackupFilesDir: File,
    private val renderer: CrashMarkerRenderer = CrashMarkerRenderer(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val elapsedBudgetNanos: Long = DEFAULT_ELAPSED_BUDGET_NANOS,
) : CrashMarkerSink {
    private val directory = noBackupFilesDir.resolve("client-diagnostics/crash-markers")

    init {
        require(elapsedBudgetNanos > 0)
    }

    override fun write(thread: Thread, throwable: Throwable, runtime: CrashRuntimeSnapshot) {
        val startedAt = nanoTime()
        val occurredAt = nowMs()
        val bytes = renderer.render(thread, throwable, runtime, occurredAt) { elapsed(startedAt) }
        if (elapsed(startedAt)) return
        if (!(directory.mkdirs() || directory.isDirectory)) return
        if (elapsed(startedAt)) return

        val stem = "jvm-${occurredAt.coerceAtLeast(0)}-${thread.stableId().coerceAtLeast(0)}"
        val temporary = directory.resolve(".$stem.tmp")
        val published = directory.resolve("$stem.json")
        try {
            FileOutputStream(temporary, false).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (elapsed(startedAt)) return
            atomicRename(temporary, published)
            syncDirectory(directory)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun elapsed(startedAt: Long): Boolean = nanoTime() - startedAt > elapsedBudgetNanos

    private fun atomicRename(source: File, target: File) {
        val renamedByOs = runCatching {
            Os.rename(source.absolutePath, target.absolutePath)
            !source.exists() && target.exists()
        }.getOrDefault(false)
        if (!renamedByOs) {
            if (target.exists() && !target.delete()) return
            source.renameTo(target)
        }
    }

    private fun syncDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        runCatching {
            descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            Os.fsync(checkNotNull(descriptor))
        }
        runCatching { descriptor?.let(Os::close) }
    }

    private companion object {
        const val DEFAULT_ELAPSED_BUDGET_NANOS = 150_000_000L
    }
}

object CrashCapture {
    private val installed = AtomicBoolean(false)
    private val runtime = AtomicReference(CrashRuntimeSnapshot.empty())
    private val logBuffer = AtomicReference<DiagnosticsLogBuffer?>(null)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val writer = FileCrashMarkerWriter(context.noBackupFilesDir)
        val handler = CrashExceptionHandler(
            markerSink = CrashMarkerSink { thread, throwable, snapshot ->
                // Once the runtime privacy gate is closed, no raw unbound crash
                // marker should be created. The same file gate serializes this
                // decision and publication with close+purge during identity change.
                if (snapshot.identityKey != null && snapshot.binding != null) {
                    writer.write(thread, throwable, snapshot)
                }
            },
            runtimeSnapshot = {
                runtime.get().let { snapshot ->
                    if (snapshot.identityKey == null) snapshot else snapshot.copy(logBuffer = logBuffer.get())
                }
            },
            previous = previous,
            writeGate = JVM_CRASH_MARKER_FILE_GATE,
        )
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    internal fun installLogBuffer(value: DiagnosticsLogBuffer) {
        logBuffer.set(value)
    }

    fun updateSnapshot(snapshot: CrashRuntimeSnapshot) {
        JVM_CRASH_MARKER_FILE_GATE.withLock {
            runtime.set(
                snapshot.copy(
                    playbackSessionIds = snapshot.playbackSessionIds.toList(),
                    logLines = snapshot.logLines.toList(),
                    redactionTokens = snapshot.redactionTokens.filter(String::isNotEmpty).toList(),
                ),
            )
        }
    }

    fun closeGate() {
        JVM_CRASH_MARKER_FILE_GATE.withLock {
            runtime.set(CrashRuntimeSnapshot.empty())
        }
    }

    fun updatePlaybackSessionIds(identityKey: DiagnosticsIdentityKey, sessionIds: List<String>) {
        JVM_CRASH_MARKER_FILE_GATE.withLock {
            runtime.updateAndGet { current ->
                if (current.identityKey == identityKey) {
                    current.copy(
                        playbackSessionIds = if (
                            current.binding?.destinationKind == DiagnosticsDestinationKind.HOSTED
                        ) {
                            emptyList()
                        } else {
                            sessionIds.toList()
                        },
                    )
                } else {
                    current
                }
            }
        }
    }

    internal fun currentSnapshotForTests(): CrashRuntimeSnapshot =
        JVM_CRASH_MARKER_FILE_GATE.withLock(runtime::get)
}

private fun PendingReportBinding.bounded(): PendingReportBinding = copy(
    serverInstanceId = serverInstanceId.truncateUtf8(128),
    accountUserId = accountUserId.truncateUtf8(128),
    profileId = profileId?.truncateUtf8(128),
    ownershipGeneration = ownershipGeneration.coerceAtLeast(0),
)

private class BoundedUtf8Builder(private val maxBytes: Int) {
    private val builder = StringBuilder()
    private var usedBytes = 0

    fun append(value: String): Boolean {
        val remaining = maxBytes - usedBytes
        if (remaining <= 0) return false
        val bounded = value.truncateUtf8(remaining)
        builder.append(bounded)
        usedBytes += bounded.encodeToByteArray().size
        return bounded.length == value.length
    }

    override fun toString(): String = builder.toString()
}

@Suppress("DEPRECATION")
private fun Thread.stableId(): Long = id

private fun String.truncateUtf8(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (encodeToByteArray().size <= maxBytes) return this
    val result = StringBuilder(length.coerceAtMost(maxBytes))
    var index = 0
    var usedBytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val value = String(Character.toChars(codePoint))
        val bytes = value.encodeToByteArray().size
        if (usedBytes + bytes > maxBytes) break
        result.append(value)
        usedBytes += bytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
    return this
}
