package org.siloserver.silo.common.diagnostics

import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

class FileDiagnosticsCaptureController(
    private val logBuffer: DiagnosticsLogBuffer,
    private val fileLogger: DiagnosticsFileLogger,
    private val breadcrumbJournal: BreadcrumbJournal? = null,
    private val reports: PendingReportStore,
    private val deviceSnapshots: DeviceSnapshotCollector,
    private val deviceSnapshotCache: DeviceSnapshotCache,
    private val environment: ExitReportEnvironment,
    private val playbackSessions: DiagnosticsPlaybackSessionTracker = DiagnosticsPlaybackSessionTracker(),
    private val performanceCapture: DiagnosticsPerformanceCapture = DiagnosticsPerformanceCapture.None,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { SiloLog.captureSessionId },
) : DiagnosticsCaptureController {
    private val generation = AtomicLong(0)
    private val active = AtomicReference<OwnedCapture?>(null)

    override fun closeGate() {
        breadcrumbJournal?.closeGate()
        setDetailedCaptureEnabled(false)
        SiloLog.installSink(logBuffer)
        logBuffer.rotateGeneration()
    }

    override suspend fun start(context: DiagnosticsCaptureContext): ActiveDiagnosticsCapture {
        require(context.profileEligible)
        active.get()?.let { previous -> cancelOwned(previous) }
        closeGate()
        val next = generation.incrementAndGet()
        val capture = ActiveDiagnosticsCapture(next, context.identityKey, nowMs())
        fileLogger.start(next)
        val owned = OwnedCapture(capture, debugLogging = false)
        if (!active.compareAndSet(null, owned)) {
            runCatching { fileLogger.cancel(next) }
            error("diagnostics capture started concurrently")
        }
        SiloLog.installSink(FanOutDiagnosticsLogSink(logBuffer, fileLogger))
        setDetailedCaptureEnabled(true)
        return capture
    }

    override suspend fun stop(
        active: ActiveDiagnosticsCapture,
        context: DiagnosticsCaptureContext,
    ): PendingReport? {
        require(active.identityKey == context.identityKey) { "diagnostics capture identity changed" }
        val owned = this.active.get()
        check(owned?.capture == active && !owned.debugLogging && this.active.compareAndSet(owned, null)) {
            "diagnostics capture is no longer active"
        }
        setDetailedCaptureEnabled(false)
        SiloLog.installSink(logBuffer)
        val frozen = try {
            fileLogger.freeze(active.generation)
        } catch (error: Throwable) {
            logBuffer.rotateGeneration()
            throw error
        }
        return try {
            saveManual(
                context = context,
                capturedAtEpochMs = nowMs(),
                logBytes = frozen.files.readBoundedLogBytes(),
                droppedLines = frozen.droppedCount,
                debugLogging = true,
            )
        } finally {
            try {
                fileLogger.deleteFrozen(frozen)
            } finally {
                logBuffer.rotateGeneration()
            }
        }
    }

    override suspend fun cancel(active: ActiveDiagnosticsCapture) {
        val owned = this.active.get()?.takeIf { it.capture == active } ?: return
        cancelOwned(owned)
    }

    override suspend fun setDebugLogging(context: DiagnosticsCaptureContext?, enabled: Boolean) {
        val current = active.get()
        if (!enabled || context == null || !context.profileEligible) {
            if (current?.debugLogging == true) cancelOwned(current)
            return
        }
        if (current?.debugLogging == true && current.capture.identityKey == context.identityKey) return
        current?.let { cancelOwned(it) }
        closeGate()
        val next = generation.incrementAndGet()
        val capture = ActiveDiagnosticsCapture(next, context.identityKey, nowMs())
        fileLogger.start(next)
        val owned = OwnedCapture(capture, debugLogging = true)
        if (!active.compareAndSet(null, owned)) {
            runCatching { fileLogger.cancel(next) }
            error("diagnostics debug logging started concurrently")
        }
        SiloLog.installSink(FanOutDiagnosticsLogSink(logBuffer, fileLogger))
        setDetailedCaptureEnabled(true)
    }

    override suspend fun setPersistentBreadcrumbs(context: DiagnosticsCaptureContext?, enabled: Boolean) {
        if (enabled && context?.profileEligible == true) {
            breadcrumbJournal?.setEnabled(context.identityKey)
        } else {
            breadcrumbJournal?.closeGate()
        }
    }

    private suspend fun cancelOwned(owned: OwnedCapture) {
        if (!active.compareAndSet(owned, null)) return
        setDetailedCaptureEnabled(false)
        SiloLog.installSink(logBuffer)
        runCatching { fileLogger.cancel(owned.capture.generation) }
        logBuffer.rotateGeneration()
    }

    private fun setDetailedCaptureEnabled(enabled: Boolean) {
        DiagnosticsCaptureDetailState.setEnabled(enabled)
        performanceCapture.setDetailedCaptureEnabled(enabled)
    }

    override suspend fun captureNow(context: DiagnosticsCaptureContext): PendingReport? {
        require(context.profileEligible)
        val snapshot = logBuffer.snapshot()
        val logBytes = snapshot.lines
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(separator = "\n", postfix = "\n")
            ?.encodeToByteArray()
        return saveManual(
            context = context,
            capturedAtEpochMs = nowMs(),
            logBytes = logBytes,
            droppedLines = snapshot.droppedCount + snapshot.tornCount,
            debugLogging = false,
        )
    }

    override suspend fun purgeCurrentEvidence() {
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            runCatching { block() }.onFailure { error -> if (failure == null) failure = error }
        }
        val owned = active.get()
        if (owned != null) attempt { cancelOwned(owned) }
        attempt { fileLogger.purgeStoredEvidence() }
        attempt { breadcrumbJournal?.purge() }
        attempt { logBuffer.clear() }
        failure?.let { throw it }
    }

    override suspend fun reconcileStoredEvidence() {
        fileLogger.reconcileStoredEvidence()
    }

    private fun saveManual(
        context: DiagnosticsCaptureContext,
        capturedAtEpochMs: Long,
        logBytes: ByteArray?,
        droppedLines: Long,
        debugLogging: Boolean,
    ): PendingReport? = runCatching {
        val snapshot = deviceSnapshots.capture(DiagnosticsDeviceProvenance.PRE_FAILURE)
        deviceSnapshotCache.update(snapshot)
        val artifacts = linkedMapOf(
            DEVICE_FILE to JSON.encodeToString(snapshot).encodeToByteArray(),
        )
        logBytes?.takeIf(ByteArray::isNotEmpty)?.let { artifacts[LOGS_FILE] = it }
        val captureSessionId = sessionIdFactory().take(128)
        val binding = PendingReportBinding(
            serverInstanceId = context.binding.serverInstanceId,
            accountUserId = context.binding.accountUserId,
            profileId = context.profileId,
            ownershipGeneration = context.ownershipGeneration,
            destinationKind = context.destinationKind,
        )
        val manifest = DiagnosticsManifest(
            schemaVersion = 1,
            report = DiagnosticsReport(
                type = DiagnosticsReportType.MANUAL,
                capturedAt = capturedAtEpochMs.toRfc3339(),
                captureSessionId = captureSessionId,
                appVersion = environment.appVersion.take(64),
                appBuild = environment.appBuild.take(64),
                platform = environment.platform,
                osVersion = environment.osVersion.take(128),
                profileId = context.profileId?.take(128),
            ),
            destination = DiagnosticsDestination(context.binding.serverInstanceId),
            consent = DiagnosticsConsent(DiagnosticsConsentMode.MANUAL, context.noticeVersion),
            deviceSummary = environment.deviceSummary,
            playbackSessionIds = if (context.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                emptyList()
            } else {
                playbackSessions.snapshot()
            },
            logSummary = DiagnosticsLogSummaryBuilder.build(logBytes, droppedLines, debugLogging),
            archive = DiagnosticsArchive(
                entries = CANONICAL_ORDER.filter { it == MANIFEST_FILE || it in artifacts },
                bytes = 0,
                uncompressedBytes = 0,
                sha256 = "0".repeat(64),
            ),
        )
        val fingerprint = sha256(
            listOf(
                "manual",
                context.binding.serverInstanceId,
                context.binding.accountUserId,
                context.profileId.orEmpty(),
                capturedAtEpochMs.toString(),
                captureSessionId,
            ).joinToString("\u0000").encodeToByteArray(),
        )
        reports.save(PendingReportCapture(binding, manifest, artifacts, fingerprint, capturedAtEpochMs))
    }.getOrNull()

    private class FanOutDiagnosticsLogSink(
        private vararg val sinks: DiagnosticsLogSink,
    ) : DiagnosticsLogSink {
        override fun offer(renderedJsonLine: String) {
            sinks.forEach { sink -> runCatching { sink.offer(renderedJsonLine) } }
        }
    }

    private data class OwnedCapture(
        val capture: ActiveDiagnosticsCapture,
        val debugLogging: Boolean,
    )

    private companion object {
        const val DEVICE_FILE = "device.json"
        const val LOGS_FILE = "logs.jsonl"
        const val MANIFEST_FILE = "manifest.json"
        const val MAX_LOG_BYTES = 10 * 1_024 * 1_024
        val JSON = Json { encodeDefaults = true; explicitNulls = false }
        val CANONICAL_ORDER = listOf(
            MANIFEST_FILE,
            DEVICE_FILE,
            LOGS_FILE,
            "crash/summary.json",
            "crash/stack.txt",
            "crash/tombstone.pb",
            "crash/metrickit.json",
            "breadcrumbs.jsonl",
        )

        fun List<File>.readBoundedLogBytes(): ByteArray? {
            if (isEmpty()) return null
            val output = ByteArrayOutputStream()
            for (file in sortedBy(File::getName)) {
                val remaining = MAX_LOG_BYTES - output.size()
                if (remaining <= 0) break
                file.inputStream().use { input ->
                    val buffer = ByteArray(minOf(8 * 1_024, remaining))
                    var left = remaining
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, left))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        left -= read
                    }
                }
            }
            return output.takeIf { it.size() > 0 }?.toByteArray()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}

private fun Long.toRfc3339(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date(this@toRfc3339))
}
