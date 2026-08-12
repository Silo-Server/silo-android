package org.siloserver.silo.common.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashInfo
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSentinel
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

object AndroidExitReason {
    const val JVM_CRASH = 4 // ApplicationExitInfo.REASON_CRASH
    const val NATIVE_CRASH = 5 // ApplicationExitInfo.REASON_CRASH_NATIVE
    const val ANR = 6 // ApplicationExitInfo.REASON_ANR
}

interface AndroidExitInfoRecord {
    val reason: Int
    val timestampMs: Long
    val pid: Int
    val processName: String
    val status: Int
    val processStateSummary: ByteArray?
    fun trace(maxBytes: Int): ByteArray?
}

fun interface AndroidExitInfoSource {
    fun records(): List<AndroidExitInfoRecord>
}

class FrameworkAndroidExitInfoSource(context: Context) : AndroidExitInfoSource {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)

    override fun records(): List<AndroidExitInfoRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activityManager == null) return emptyList()
        return activityManager.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
            .map(::FrameworkExitInfoRecord)
    }

    private class FrameworkExitInfoRecord(
        private val record: ApplicationExitInfo,
    ) : AndroidExitInfoRecord {
        override val reason: Int get() = record.reason
        override val timestampMs: Long get() = record.timestamp
        override val pid: Int get() = record.pid
        override val processName: String get() = record.processName.orEmpty()
        override val status: Int get() = record.status
        override val processStateSummary: ByteArray? get() = record.processStateSummary?.copyOf()

        override fun trace(maxBytes: Int): ByteArray? {
            require(maxBytes > 0)
            if (reason == AndroidExitReason.NATIVE_CRASH && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return record.traceInputStream?.use { input ->
                val output = ByteArrayOutputStream(maxBytes.coerceAtMost(16 * 1_024))
                val buffer = ByteArray(8 * 1_024)
                var remaining = maxBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    if (read == 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toByteArray()
            }
        }
    }

    private companion object {
        const val MAX_RECORDS = 64
    }
}

class AndroidProcessStateSummaryPublisher(context: Context) : ProcessStateSummaryPublisher {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)

    override fun publish(summary: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activityManager != null) {
            activityManager.setProcessStateSummary(summary.copyOf())
        }
    }
}

interface JvmCrashMarkerSource {
    fun records(): List<JvmCrashMarkerRecord>
    fun delete(marker: JvmCrashMarkerRecord)
    fun purge(binding: DiagnosticsBinding) {
        records().filter { marker -> marker.binding?.binding == binding }.forEach(::delete)
    }
}

class FileJvmCrashMarkerSource internal constructor(
    noBackupFilesDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val fileGate: JvmCrashMarkerFileGate,
    private val deleteFile: (File) -> Boolean,
    private val syncDirectory: (File) -> Unit,
    private val listFiles: (File) -> Array<File>?,
) : JvmCrashMarkerSource {
    private val directory = noBackupFilesDir.resolve("client-diagnostics/crash-markers")

    constructor(noBackupFilesDir: File) : this(
        noBackupFilesDir = noBackupFilesDir,
        nowMs = System::currentTimeMillis,
        fileGate = JVM_CRASH_MARKER_FILE_GATE,
        deleteFile = File::delete,
        syncDirectory = ::syncJvmCrashMarkerDirectory,
        listFiles = File::listFiles,
    )

    /**
     * Enforces the raw-marker retention boundary without turning a marker into a report. This is
     * called before network or identity resolution on every coordinator refresh, including while
     * offline, ineligible, or opted out.
     */
    fun reconcile() {
        fileGate.withLock { reconciledRecordsLocked() }
    }

    override fun records(): List<JvmCrashMarkerRecord> =
        fileGate.withLock { reconciledRecordsLocked() }

    override fun delete(marker: JvmCrashMarkerRecord) {
        fileGate.withLock {
            val sourceFileName = marker.sourceFileName
                ?.takeIf(MARKER_NAME::matches)
                ?: return@withLock
            val file = checkNotNull(listFiles(directory)) {
                "unable to enumerate JVM crash markers"
            }.firstOrNull { entry -> entry.name == sourceFileName } ?: return@withLock
            deleteStrict(file)
            syncAndVerify(files = listOf(file))
        }
    }

    override fun purge(binding: DiagnosticsBinding) {
        fileGate.withLock {
            if (!directory.exists()) return@withLock
            check(!isSymbolicLink(directory)) { "JVM crash marker path is a symbolic link" }
            check(directory.isDirectory) { "JVM crash marker path is not a directory" }
            val removed = checkNotNull(listFiles(directory)) {
                "unable to enumerate JVM crash markers"
            }
                .filter { file ->
                    when {
                        !isBoundedMarkerFile(file) -> true
                        else -> decodeMarker(file)?.binding?.binding?.let { it == binding } ?: true
                    }
                }
            removed.forEach(::deleteStrict)
            syncAndVerify(removed)
        }
    }

    fun purgeAll() {
        fileGate.withLock {
            if (!directory.exists()) return@withLock
            check(!isSymbolicLink(directory)) { "JVM crash marker path is a symbolic link" }
            check(directory.isDirectory) { "JVM crash marker path is not a directory" }
            val removed = checkNotNull(listFiles(directory)) {
                "unable to enumerate JVM crash markers"
            }.toList()
            removed.forEach(::deleteStrict)
            syncAndVerify(removed)
        }
    }

    private fun isBoundedMarkerFile(file: File): Boolean =
        file.isFile &&
            !isSymbolicLink(file) &&
            MARKER_NAME.matches(file.name) &&
            file.length() in 1..CrashMarkerRenderer.MAX_MARKER_BYTES.toLong()

    private fun isSymbolicLink(file: File): Boolean =
        File(checkNotNull(file.parentFile).canonicalFile, file.name).let { canonicalParentEntry ->
            canonicalParentEntry.absoluteFile != canonicalParentEntry.canonicalFile
        }

    private fun reconciledRecordsLocked(): List<JvmCrashMarkerRecord> {
        if (!directory.exists()) return emptyList()
        check(!isSymbolicLink(directory)) { "JVM crash marker path is a symbolic link" }
        check(directory.isDirectory) { "JVM crash marker path is not a directory" }
        val files = checkNotNull(listFiles(directory)) {
            "unable to enumerate JVM crash markers"
        }.toList()
        val now = nowMs()
        check(now >= 0) { "JVM crash marker clock must be non-negative" }
        val invalid = mutableListOf<File>()
        val decoded = buildList {
            files.forEach { file ->
                if (!isBoundedMarkerFile(file)) {
                    invalid += file
                    return@forEach
                }
                val marker = decodeMarker(file)
                if (marker == null || !isWithinRetention(marker.occurredAtEpochMs, now)) {
                    invalid += file
                } else {
                    add(file to marker)
                }
            }
        }
        val retained = decoded
            .sortedWith(compareBy<Pair<File, JvmCrashMarkerRecord>>(
                { (_, marker) -> marker.occurredAtEpochMs },
                { (file, _) -> file.name },
            ))
            .takeLast(MAX_MARKERS)
        val retainedFiles = retained.mapTo(mutableSetOf()) { (file, _) -> file }
        val removed = invalid + decoded.map(Pair<File, JvmCrashMarkerRecord>::first)
            .filterNot(retainedFiles::contains)
        removed.forEach(::deleteStrict)
        syncAndVerify(removed)
        return retained.map(Pair<File, JvmCrashMarkerRecord>::second)
    }

    private fun isWithinRetention(occurredAtEpochMs: Long, nowEpochMs: Long): Boolean {
        val oldestAllowed = (nowEpochMs - RETENTION_MS).coerceAtLeast(0)
        val newestAllowed = if (nowEpochMs > Long.MAX_VALUE - MAX_FUTURE_SKEW_MS) {
            Long.MAX_VALUE
        } else {
            nowEpochMs + MAX_FUTURE_SKEW_MS
        }
        return occurredAtEpochMs in oldestAllowed..newestAllowed
    }

    private fun decodeMarker(file: File): JvmCrashMarkerRecord? =
        runCatching { JSON.decodeFromString<JvmCrashMarkerRecord>(file.readText()) }
            .getOrNull()
            ?.takeIf { marker ->
                marker.schemaVersion == 1 &&
                    marker.occurredAtEpochMs >= 0 &&
                    marker.occurredAtEpochMs == markerTimestamp(file.name)
            }
            ?.copy(sourceFileName = file.name)

    private fun markerTimestamp(fileName: String): Long? =
        MARKER_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

    private fun deleteStrict(file: File) {
        check(deleteFile(file)) { "unable to delete JVM crash marker ${file.name}" }
        check(!directoryEntryExists(file.name)) { "JVM crash marker still exists after deletion: ${file.name}" }
    }

    private fun syncAndVerify(files: List<File>) {
        if (files.isEmpty()) return
        syncDirectory(directory)
        check(files.none { file -> directoryEntryExists(file.name) }) {
            "JVM crash marker deletion was not durable"
        }
    }

    private fun directoryEntryExists(name: String): Boolean =
        checkNotNull(listFiles(directory)) { "unable to verify JVM crash marker deletion" }
            .any { entry -> entry.name == name }

    private companion object {
        const val MAX_MARKERS = 3
        const val RETENTION_MS = PENDING_DIAGNOSTICS_RETENTION_DAYS * 24L * 60 * 60 * 1_000
        const val MAX_FUTURE_SKEW_MS = 5L * 60 * 1_000
        val MARKER_NAME = Regex("^jvm-([0-9]+)-[0-9]+\\.json$")
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

private fun syncJvmCrashMarkerDirectory(directory: File) {
    var descriptor: FileDescriptor? = null
    try {
        descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        Os.fsync(checkNotNull(descriptor))
    } finally {
        descriptor?.let(Os::close)
    }
}

data class ExitReportEnvironment(
    val appVersion: String,
    val appBuild: String,
    val platform: DiagnosticsPlatform,
    val osVersion: String,
    val deviceSummary: DiagnosticsDeviceSummary,
)

class ExitInfoCollector(
    private val source: AndroidExitInfoSource,
    private val ledger: DiagnosticsRunLedger,
    private val reports: PendingReportStore,
    private val markers: JvmCrashMarkerSource,
    private val environment: ExitReportEnvironment,
    private val deviceSnapshotBytes: () -> ByteArray?,
    private val noticeVersion: () -> Int,
    private val consentMode: () -> DiagnosticsConsentMode = { DiagnosticsConsentMode.PROMPT },
    private val redactionTokens: () -> List<String> = { emptyList() },
    private val breadcrumbs: DiagnosticsBreadcrumbSource = DiagnosticsBreadcrumbSource.None,
) {
    suspend fun collect(): List<PendingReport> {
        val exits = mutableListOf<CollectedExit>()
        runCatching(source::records).getOrDefault(emptyList()).take(MAX_EXIT_RECORDS).forEach { record ->
            if (record.reason !in SUPPORTED_REASONS || record.timestampMs < 0) return@forEach
            val runToken = runCatching { record.runToken() }.getOrNull() ?: return@forEach
            val run = ledger.find(runToken) ?: return@forEach
            if (!run.profileEligible) return@forEach
            val trace = runCatching { record.trace(MAX_TRACE_BYTES) }.getOrNull()
            exits += CollectedExit(record, trace, run)
        }
        val markerRecords = markers.records()
        val saved = mutableListOf<PendingReport>()

        markerRecords.forEach { marker ->
            val runToken = marker.runToken
            val run = runToken?.let { ledger.find(it) }
            if (run == null || !run.profileEligible || !marker.matches(run)) {
                markers.delete(marker)
                return@forEach
            }
            val matchingExit = exits.firstOrNull { exit ->
                exit.record.reason == AndroidExitReason.JVM_CRASH &&
                    exit.run.token == runToken &&
                    absoluteDifference(exit.record.timestampMs, marker.occurredAtEpochMs) <= JVM_MATCH_WINDOW_MS
            }
            val fingerprint = matchingExit?.let(::exitFingerprint) ?: markerFingerprint(marker)
            if (reports.hasSeenFingerprint(fingerprint)) {
                markers.delete(marker)
                return@forEach
            }
            runCatching { saveMarker(marker, run, fingerprint) }.getOrNull()?.let { report ->
                saved += report
                markers.delete(marker)
            }
        }

        exits.forEach { exit ->
            val fingerprint = exitFingerprint(exit)
            if (reports.hasSeenFingerprint(fingerprint)) return@forEach
            runCatching { saveExit(exit, exit.run, fingerprint) }.getOrNull()?.let(saved::add)
        }
        return saved
    }

    private fun saveMarker(
        marker: JvmCrashMarkerRecord,
        run: DiagnosticsRunRecord,
        fingerprint: String,
    ): PendingReport? {
        val redactor = DiagnosticsRedactor(
            sensitiveValues = redactionTokens().filter(String::isNotEmpty).toSet(),
        )
        val safeStack = redactor.sanitize(marker.stack).truncateUtf8ForExit(MAX_TEXT_TRACE_BYTES)
        val safeThrowableType = redactor.sanitize(marker.throwableType).truncateUtf8ForExit(MAX_STACK_EXCERPT_BYTES)
        val safeThreadName = redactor.sanitize(marker.threadName).truncateUtf8ForExit(128)
        val binding = run.pendingBinding()
        val artifacts = linkedMapOf<String, ByteArray>()
        artifacts[DEVICE_FILE] = marker.deviceSnapshotJson?.encodeToByteArray()
            ?: deviceSnapshotBytes()
            ?: fallbackDeviceSnapshot(marker.occurredAtEpochMs)
        artifacts[CRASH_SUMMARY_FILE] = crashSummaryBytes(
            kind = "jvm_crash",
            processName = null,
            pid = null,
            status = null,
        )
        artifacts[CRASH_STACK_FILE] = safeStack.encodeToByteArray()
        sanitizedLogBytes(marker.logLines, redactor)?.let { artifacts[LOGS_FILE] = it }
        val manifest = manifest(
            reportType = DiagnosticsReportType.CRASH,
            crashSource = DiagnosticsCrashSource.UEH,
            provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
            capturedAtEpochMs = marker.occurredAtEpochMs,
            captureSessionId = marker.captureSessionId ?: run.captureSessionId,
            profileId = run.profileId,
            binding = binding,
            summary = safeThrowableType,
            stackExcerpt = safeStack.truncateUtf8ForExit(MAX_STACK_EXCERPT_BYTES),
            thread = safeThreadName,
            foreground = marker.foreground,
            playbackSessionIds = marker.playbackSessionIds,
            artifacts = artifacts,
            droppedLines = marker.logDroppedCount + marker.logTornCount,
        )
        return save(binding, manifest, artifacts, fingerprint, marker.occurredAtEpochMs)
    }

    private fun sanitizedLogBytes(
        lines: List<String>,
        redactor: DiagnosticsRedactor,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        for (line in lines) {
            val sanitized = runCatching {
                val decoded = LOG_JSON.decodeFromString<DiagnosticsLogLine>(line)
                val attributes = decoded.attributes?.mapValues { (_, value) ->
                    if (value is JsonPrimitive && value.isString) {
                        JsonPrimitive(redactor.sanitize(value.content))
                    } else {
                        value
                    }
                }
                val encoded = LOG_JSON.encodeToString(
                    decoded.copy(
                        tag = redactor.sanitize(decoded.tag),
                        message = redactor.sanitize(decoded.message),
                        attributes = attributes,
                    ),
                )
                encoded.takeIf { it.encodeToByteArray().size <= MAX_LOG_LINE_BYTES }
            }.getOrNull() ?: continue
            val bytes = (sanitized + "\n").encodeToByteArray()
            if (output.size() + bytes.size > MAX_CRASH_LOG_BYTES) break
            output.write(bytes)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private fun saveExit(
        collected: CollectedExit,
        run: DiagnosticsRunRecord,
        fingerprint: String,
    ): PendingReport? {
        val exit = collected.record
        val classification = classify(exit.reason) ?: return null
        val trace = collected.trace
        val binding = run.pendingBinding()
        val artifacts = linkedMapOf<String, ByteArray>()
        artifacts[DEVICE_FILE] = deviceSnapshotBytes() ?: fallbackDeviceSnapshot(exit.timestampMs)
        artifacts[CRASH_SUMMARY_FILE] = crashSummaryBytes(
            kind = classification.label,
            processName = exit.processName,
            pid = exit.pid,
            status = exit.status,
        )
        runCatching { breadcrumbs.linesForRun(run.captureSessionId, run.identityKey()) }
            .getOrDefault(emptyList())
            .takeIf(List<String>::isNotEmpty)?.let { lines ->
            artifacts[BREADCRUMBS_FILE] = (lines.joinToString("\n") + "\n").encodeToByteArray()
        }
        var stackExcerpt: String? = null
        when {
            classification.reportType == DiagnosticsReportType.NATIVE_CRASH && trace != null ->
                artifacts[CRASH_TOMBSTONE_FILE] = trace
            trace != null -> {
                val text = strictUtf8(trace)?.let {
                    DiagnosticsRedactor(sensitiveValues = redactionTokens().filter(String::isNotEmpty).toSet()).sanitize(it)
                } ?: REDACTION_FAILURE_SENTINEL
                val bytes = text.truncateUtf8ForExit(MAX_TEXT_TRACE_BYTES).encodeToByteArray()
                artifacts[CRASH_STACK_FILE] = bytes
                stackExcerpt = text.truncateUtf8ForExit(MAX_STACK_EXCERPT_BYTES)
            }
        }
        val manifest = manifest(
            reportType = classification.reportType,
            crashSource = DiagnosticsCrashSource.EXIT_INFO,
            provenance = DiagnosticsCrashProvenance.POST_RESTART,
            capturedAtEpochMs = exit.timestampMs,
            captureSessionId = run.captureSessionId,
            profileId = run.profileId,
            binding = binding,
            summary = classification.summary,
            stackExcerpt = stackExcerpt,
            thread = null,
            foreground = null,
            playbackSessionIds = emptyList(),
            artifacts = artifacts,
            droppedLines = 0,
        )
        return save(binding, manifest, artifacts, fingerprint, exit.timestampMs)
    }

    private fun save(
        binding: PendingReportBinding,
        manifest: DiagnosticsManifest,
        artifacts: Map<String, ByteArray>,
        fingerprint: String,
        capturedAtEpochMs: Long,
    ): PendingReport? = runCatching {
        reports.save(PendingReportCapture(binding, manifest, artifacts, fingerprint, capturedAtEpochMs))
    }.getOrNull()

    private fun manifest(
        reportType: DiagnosticsReportType,
        crashSource: DiagnosticsCrashSource,
        provenance: DiagnosticsCrashProvenance,
        capturedAtEpochMs: Long,
        captureSessionId: String,
        profileId: String?,
        binding: PendingReportBinding,
        summary: String,
        stackExcerpt: String?,
        thread: String?,
        foreground: Boolean?,
        playbackSessionIds: List<String>,
        artifacts: Map<String, ByteArray>,
        droppedLines: Long,
    ): DiagnosticsManifest {
        val logSummary = DiagnosticsLogSummaryBuilder.build(
            logBytes = artifacts[LOGS_FILE],
            droppedLines = droppedLines,
            debugLogging = false,
        )
        return DiagnosticsManifest(
            schemaVersion = 1,
            report = DiagnosticsReport(
                type = reportType,
                capturedAt = rfc3339(capturedAtEpochMs),
                captureSessionId = captureSessionId.take(128),
                appVersion = environment.appVersion.take(64),
                appBuild = environment.appBuild.take(64),
                platform = environment.platform,
                osVersion = environment.osVersion.take(128),
                profileId = if (binding.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                    null
                } else {
                    profileId?.take(128)
                },
            ),
            destination = DiagnosticsDestination(binding.serverInstanceId),
            consent = DiagnosticsConsent(consentMode(), noticeVersion().coerceAtLeast(1)),
            crash = DiagnosticsCrashInfo(
                summary = summary.truncateUtf8ForExit(MAX_STACK_EXCERPT_BYTES),
                stackExcerpt = stackExcerpt,
                thread = thread?.truncateUtf8ForExit(128),
                foreground = foreground,
                source = crashSource,
                provenance = provenance,
                occurredAt = rfc3339(capturedAtEpochMs),
            ),
            deviceSummary = environment.deviceSummary,
            playbackSessionIds = if (binding.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                emptyList()
            } else {
                playbackSessionIds.take(20).map { it.take(128) }
            },
            logSummary = logSummary,
            archive = DiagnosticsArchive(
                entries = CANONICAL_ARCHIVE_ORDER.filter { it == "manifest.json" || it in artifacts },
                bytes = 0,
                uncompressedBytes = 0,
                sha256 = "0".repeat(64),
            ),
        )
    }

    private fun AndroidExitInfoRecord.runToken(): String? {
        val summary = processStateSummary ?: return null
        if (summary.size != 32) return null
        val token = strictUtf8(summary) ?: return null
        return token.takeIf(RUN_TOKEN_PATTERN::matches)
    }

    private fun JvmCrashMarkerRecord.matches(run: DiagnosticsRunRecord): Boolean =
        binding == run.pendingBinding() &&
            captureSessionId == run.captureSessionId &&
            runToken == run.token

    private fun DiagnosticsRunRecord.pendingBinding() = PendingReportBinding(
        serverInstanceId = binding.serverInstanceId,
        accountUserId = binding.accountUserId,
        profileId = profileId,
        ownershipGeneration = ownershipGeneration,
        destinationKind = destinationKind,
    )

    private fun DiagnosticsRunRecord.identityKey() = DiagnosticsIdentityKey(
        binding = binding,
        profileId = profileId,
        ownershipGeneration = ownershipGeneration,
    )

    internal data class ExitClassification(
        val reportType: DiagnosticsReportType,
        val label: String,
        val summary: String,
    )

    internal data class CollectedExit(
        val record: AndroidExitInfoRecord,
        val trace: ByteArray?,
        val run: DiagnosticsRunRecord,
    )

    private companion object {
        val SUPPORTED_REASONS = setOf(AndroidExitReason.JVM_CRASH, AndroidExitReason.NATIVE_CRASH, AndroidExitReason.ANR)
        val RUN_TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
        const val JVM_MATCH_WINDOW_MS = 5 * 60 * 1_000L
        const val MAX_EXIT_RECORDS = 64
        const val MAX_TRACE_BYTES = 512 * 1_024
        const val MAX_TEXT_TRACE_BYTES = 256 * 1_024
        const val MAX_STACK_EXCERPT_BYTES = 8 * 1_024
        const val MAX_LOG_LINE_BYTES = 8 * 1_024
        const val MAX_CRASH_LOG_BYTES = 256 * 1_024
        const val DEVICE_FILE = "device.json"
        const val LOGS_FILE = "logs.jsonl"
        const val CRASH_SUMMARY_FILE = "crash/summary.json"
        const val CRASH_STACK_FILE = "crash/stack.txt"
        const val CRASH_TOMBSTONE_FILE = "crash/tombstone.pb"
        const val BREADCRUMBS_FILE = "breadcrumbs.jsonl"
        const val REDACTION_FAILURE_SENTINEL = "{\"redaction_failure\":true}\n"
        val LOG_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

private fun classify(reason: Int): ExitInfoCollector.ExitClassification? = when (reason) {
    AndroidExitReason.JVM_CRASH -> ExitInfoCollector.ExitClassification(DiagnosticsReportType.CRASH, "jvm_crash", "Android JVM crash")
    AndroidExitReason.NATIVE_CRASH -> ExitInfoCollector.ExitClassification(DiagnosticsReportType.NATIVE_CRASH, "native_crash", "Android native crash")
    AndroidExitReason.ANR -> ExitInfoCollector.ExitClassification(DiagnosticsReportType.ANR, "anr", "Android application not responding")
    else -> null
}

private fun exitFingerprint(exit: ExitInfoCollector.CollectedExit): String {
    val record = exit.record
    val traceHash = exit.trace?.let(::sha256Hex).orEmpty()
    return sha256Hex("${record.processName.take(1_024)}|${record.pid}|${record.timestampMs}|${record.reason}|${record.status}|$traceHash".encodeToByteArray())
}

private fun markerFingerprint(marker: JvmCrashMarkerRecord): String =
    sha256Hex("ueh|${marker.runToken}|${marker.occurredAtEpochMs}|${marker.throwableType}|${sha256Hex(marker.stack.encodeToByteArray())}".encodeToByteArray())

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun strictUtf8(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun crashSummaryBytes(kind: String, processName: String?, pid: Int?, status: Int?): ByteArray =
    Json.encodeToString(
        buildJsonObject {
            put("kind", kind)
            processName?.let { put("process_hash", sha256Hex(it.encodeToByteArray()).take(32)) }
            pid?.let { put("pid", it) }
            status?.let { put("status", it) }
        },
    ).encodeToByteArray()

private fun fallbackDeviceSnapshot(capturedAtEpochMs: Long): ByteArray = Json.encodeToString(
    buildJsonObject {
        put("captured_at", rfc3339(capturedAtEpochMs))
        put("provenance", DiagnosticsDeviceProvenance.POST_RESTART.name.lowercase())
        put("identity", DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
        put("display", DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
        put("audio", DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
        put("video_codecs", DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
        put("network", DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
    },
).encodeToByteArray()

private fun rfc3339(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date(epochMs.coerceAtLeast(0)))
}

private fun absoluteDifference(first: Long, second: Long): Long =
    if (first >= second) first - second else second - first

private fun String.truncateUtf8ForExit(maxBytes: Int): String {
    if (encodeToByteArray().size <= maxBytes) return this
    val output = StringBuilder(length.coerceAtMost(maxBytes))
    var index = 0
    var bytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val value = String(Character.toChars(codePoint))
        val size = value.encodeToByteArray().size
        if (bytes + size > maxBytes) break
        output.append(value)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return output.toString()
}
