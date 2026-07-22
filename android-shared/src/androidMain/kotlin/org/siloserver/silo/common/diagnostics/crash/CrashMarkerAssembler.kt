package org.siloserver.silo.common.diagnostics.crash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.common.diagnostics.DeviceSnapshotCollector
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsBinding
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.common.diagnostics.logging.BreadcrumbJournal
import org.siloserver.silo.common.diagnostics.logging.DiagRedactor
import org.siloserver.silo.common.diagnostics.sha256Hex
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import java.io.File
import java.time.Instant

/**
 * Next-launch conversion of crash markers into pending reports.
 *
 * This is the mandatory redaction choke point for UEH evidence: the marker
 * stores the raw exception message and stack (the dying thread cannot risk a
 * pathological regex backtrack), so [DiagRedactor] MUST run here before
 * anything reaches a pending report's manifest or `crash/stack.txt`. Markers
 * are deleted once consumed — a permanently unreadable marker is dropped, not
 * retried forever.
 */
class CrashMarkerAssembler(
    private val markerDir: File,
    private val pendingReportStore: PendingReportStore,
    private val deviceSnapshotCollector: DeviceSnapshotCollector,
    private val breadcrumbJournal: BreadcrumbJournal,
) {

    suspend fun assemblePendingMarkers() = withContext(Dispatchers.IO) {
        val markers = markerDir.listFiles { f -> f.name.endsWith(".json") } ?: return@withContext
        for (file in markers.sortedBy { it.lastModified() }) {
            runCatching { assembleOne(file) }
            // Consumed either way: success, dedupe, no-binding, or corrupt.
            file.delete()
        }
    }

    private fun assembleOne(file: File) {
        val marker = CrashMarker.parse(file.readBytes()) ?: return
        // No binding recorded before this crash (first launch, pre-login):
        // there is no account that consented to receive it — drop.
        val session = marker.session ?: return
        if (System.currentTimeMillis() - marker.writtenAtMs > PendingReportStore.EXPIRY_MS) return

        val fingerprint =
            "ueh|${marker.processName}|${marker.pid}|${marker.writtenAtMs}|${sha256Hex(marker.stackText)}"
        if (pendingReportStore.hasSeenFingerprint(fingerprint)) return

        val binding = DiagnosticsBinding(
            serverInstanceId = session.serverInstanceId,
            accountUserId = session.accountUserId,
        )
        val occurredAt = Instant.ofEpochMilli(marker.writtenAtMs).toString()
        val summary = DiagRedactor.sanitize(
            listOf(marker.exceptionClass, marker.exceptionMessage)
                .filter { it.isNotBlank() }
                .joinToString(": "),
            maxBytes = 8192,
        ).ifBlank { "crash" }
        val sanitizedStack = DiagRedactor.sanitize(marker.stackText, maxBytes = 512 * 1024)

        val draft = DiagnosticsManifestDraft(
            report = DiagnosticsManifest.Report(
                type = DiagnosticsReportType.CRASH,
                capturedAt = occurredAt,
                captureSessionId = marker.captureSessionId.ifBlank { "run_unknown" },
                appVersion = session.appVersion.ifBlank { "unknown" },
                appBuild = session.appBuild.ifBlank { "unknown" },
                platform = if (session.platform == "android-tv") DiagnosticsPlatform.ANDROID_TV else DiagnosticsPlatform.ANDROID,
                osVersion = session.osVersion.ifBlank { deviceSnapshotCollector.osVersion() },
                profileId = session.profileId,
            ),
            destination = DiagnosticsManifest.Destination(session.serverInstanceId),
            consent = DiagnosticsManifest.Consent(
                mode = if (session.consentMode == "always") DiagnosticsConsentMode.ALWAYS else DiagnosticsConsentMode.PROMPT,
                noticeVersion = session.noticeVersion,
            ),
            crash = DiagnosticsManifest.Crash(
                summary = summary,
                stackExcerpt = DiagRedactor.truncateUtf8(sanitizedStack, 8192),
                thread = marker.threadName.takeIf { it.isNotBlank() },
                foreground = marker.foreground,
                source = DiagnosticsCrashSource.UEH,
                provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
                occurredAt = occurredAt,
            ),
            deviceSummary = deviceSnapshotCollector.deviceSummary(),
            playbackSessionIds = marker.playbackSessionIds.take(20),
            // Recomputed from logs.jsonl at bundle-build time; placeholder here.
            logSummary = DiagnosticsManifest.LogSummary(
                lines = marker.logLines.size.toLong(),
                bytesGz = 0,
                droppedLines = marker.droppedLines,
                categories = emptyList(),
                debugLogging = false,
            ),
        )

        val artifacts = buildList {
            if (marker.logLines.isNotEmpty()) {
                add(
                    PendingReportStore.Artifact(
                        "logs.jsonl",
                        marker.logLines.joinToString("\n") { it.toString() }.encodeToByteArray(),
                    ),
                )
            }
            add(PendingReportStore.Artifact("crash/stack.txt", sanitizedStack.encodeToByteArray()))
            marker.deviceSnapshot?.let {
                add(PendingReportStore.Artifact("device.json", it.toString().encodeToByteArray()))
            }
            val crumbs = breadcrumbLinesForRun(marker.captureSessionId)
            if (crumbs.isNotEmpty()) {
                add(PendingReportStore.Artifact("breadcrumbs.jsonl", crumbs.joinToString("\n").encodeToByteArray()))
            }
        }

        val saved = pendingReportStore.save(
            PendingReportStore.PendingReportCapture(
                binding = binding,
                profileId = session.profileId,
                capturedAtEpochMs = marker.writtenAtMs,
                type = DiagnosticsReportType.CRASH,
                fingerprint = fingerprint,
                manifestDraft = draft,
                artifacts = artifacts,
            ),
        )
        // Only a capture that actually survived the cap is marked seen — an
        // immediately-evicted one must stay reportable if circumstances change.
        if (saved != null) pendingReportStore.markFingerprintSeen(fingerprint)
    }

    /** Journal lines from the crashed run only — a relaunch's own breadcrumbs are not evidence. */
    private fun breadcrumbLinesForRun(runId: String): List<String> {
        if (runId.isBlank()) return emptyList()
        return breadcrumbJournal.readAllLines().mapNotNull { lineBytes ->
            val text = lineBytes.decodeToString()
            val obj = runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            val lineRun = runCatching { obj["run"]?.jsonPrimitive?.content }.getOrNull()
            if (lineRun == runId) text else null
        }
    }

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
