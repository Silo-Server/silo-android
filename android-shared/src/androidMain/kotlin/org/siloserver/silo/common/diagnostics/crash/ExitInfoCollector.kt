package org.siloserver.silo.common.diagnostics.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.diagnostics.CrashSessionContext
import org.siloserver.silo.common.diagnostics.DeviceSnapshotCollector
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsBinding
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.common.diagnostics.logging.BreadcrumbJournal
import org.siloserver.silo.common.diagnostics.logging.DiagRedactor
import org.siloserver.silo.common.diagnostics.sha256Hex
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * API 30+ launch-time collector for deaths the in-process handler can't see:
 * ANRs and native crashes from [ActivityManager.getHistoricalProcessExitReasons].
 *
 * JVM crashes (REASON_CRASH) are deliberately skipped: the UEH marker already
 * captured them with strictly better evidence (pre-failure ring, session
 * context), and fingerprints can't correlate the same death across sources —
 * skipping is what prevents double-reporting.
 *
 * Dedupe uses a persisted fingerprint set over
 * (process, pid, timestamp, reason, status, trace hash) — not a timestamp
 * watermark, which loses same-stamp events and breaks on clock changes.
 * On API 24–29 this is a no-op and the UEH handler is the only crash source.
 */
class ExitInfoCollector(
    private val context: Context,
    private val pendingReportStore: PendingReportStore,
    private val deviceSnapshotCollector: DeviceSnapshotCollector,
    private val breadcrumbJournal: BreadcrumbJournal,
) {

    suspend fun collectOnLaunch(session: CrashSessionContext) {
        if (Build.VERSION.SDK_INT < 30) return
        withContext(Dispatchers.IO) {
            runCatching { collect(session) }
        }
    }

    @RequiresApi(30)
    private fun collect(session: CrashSessionContext) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = am.getHistoricalProcessExitReasons(null, 0, 16)
        val now = System.currentTimeMillis()
        for (info in infos) {
            if (info.processName != context.packageName) continue
            if (now - info.timestamp > PendingReportStore.EXPIRY_MS) continue
            val type = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> DiagnosticsReportType.ANR
                ApplicationExitInfo.REASON_CRASH_NATIVE -> DiagnosticsReportType.NATIVE_CRASH
                else -> continue
            }
            val trace = readTraceBounded(info)
            val traceHash = trace?.let { sha256Hex(it) } ?: "no_trace"
            val fingerprint =
                "exit_info|${info.processName}|${info.pid}|${info.timestamp}|${info.reason}|${info.status}|$traceHash"
            if (pendingReportStore.hasSeenFingerprint(fingerprint)) continue

            val saved = pendingReportStore.save(buildCapture(session, type, fingerprint, info, trace))
            if (saved != null) pendingReportStore.markFingerprintSeen(fingerprint)
        }
    }

    @RequiresApi(30)
    private fun buildCapture(
        session: CrashSessionContext,
        type: DiagnosticsReportType,
        fingerprint: String,
        info: ApplicationExitInfo,
        trace: ByteArray?,
    ): PendingReportStore.PendingReportCapture {
        val occurredAt = Instant.ofEpochMilli(info.timestamp).toString()
        val description = info.description?.takeIf { it.isNotBlank() }
        val summary = DiagRedactor.sanitize(
            when (type) {
                DiagnosticsReportType.ANR -> "ANR${description?.let { ": $it" } ?: ""}"
                else -> "Native crash (status ${info.status})${description?.let { ": $it" } ?: ""}"
            },
            maxBytes = 8192,
        )

        // ANR trace is text (goes to crash/stack.txt, redacted); a native
        // tombstone is opaque protobuf (crash/tombstone.pb, never decoded,
        // never string-scrubbed — a UTF-8 pass could corrupt it).
        val artifacts = buildList {
            if (trace != null) {
                if (type == DiagnosticsReportType.ANR) {
                    val sanitized = DiagRedactor.sanitize(trace.decodeToString(), maxBytes = 512 * 1024)
                    add(PendingReportStore.Artifact("crash/stack.txt", sanitized.encodeToByteArray()))
                } else {
                    add(PendingReportStore.Artifact("crash/tombstone.pb", trace))
                }
            }
            val snapshot = deviceSnapshotCollector.collect(DiagnosticsDeviceProvenance.POST_RESTART, deviceId = null)
            add(
                PendingReportStore.Artifact(
                    "device.json",
                    SiloJson.encodeToString(DiagnosticsDeviceSnapshot.serializer(), snapshot).encodeToByteArray(),
                ),
            )
            val crumbs = breadcrumbLinesForWindow(info.timestamp - BREADCRUMB_WINDOW_MS, info.timestamp + 10_000)
            if (crumbs.isNotEmpty()) {
                add(PendingReportStore.Artifact("breadcrumbs.jsonl", crumbs.joinToString("\n").encodeToByteArray()))
            }
        }

        val stackExcerpt = if (type == DiagnosticsReportType.ANR && trace != null) {
            DiagRedactor.sanitize(trace.decodeToString(), maxBytes = 8192)
        } else {
            null
        }

        return PendingReportStore.PendingReportCapture(
            binding = DiagnosticsBinding(session.serverInstanceId, session.accountUserId),
            profileId = session.profileId,
            capturedAtEpochMs = info.timestamp,
            type = type,
            fingerprint = fingerprint,
            manifestDraft = DiagnosticsManifestDraft(
                report = DiagnosticsManifest.Report(
                    type = type,
                    capturedAt = occurredAt,
                    captureSessionId = "run_exit_${info.timestamp}",
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
                    summary = summary.ifBlank { type.name.lowercase() },
                    stackExcerpt = stackExcerpt,
                    thread = null,
                    foreground = info.importance <= IMPORTANCE_FOREGROUND_SERVICE,
                    source = DiagnosticsCrashSource.EXIT_INFO,
                    provenance = DiagnosticsCrashProvenance.POST_RESTART,
                    occurredAt = occurredAt,
                ),
                deviceSummary = deviceSnapshotCollector.deviceSummary(),
                playbackSessionIds = emptyList(),
                logSummary = DiagnosticsManifest.LogSummary(
                    lines = 0,
                    bytesGz = 0,
                    droppedLines = 0,
                    categories = emptyList(),
                    debugLogging = false,
                ),
            ),
            artifacts = artifacts,
        )
    }

    @RequiresApi(30)
    private fun readTraceBounded(info: ApplicationExitInfo): ByteArray? = runCatching {
        info.traceInputStream?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (total + read > MAX_TRACE_BYTES) {
                    out.write(buffer, 0, MAX_TRACE_BYTES - total)
                    break
                }
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun breadcrumbLinesForWindow(fromEpochMs: Long, toEpochMs: Long): List<String> =
        breadcrumbJournal.readAllLines().mapNotNull { lineBytes ->
            val text = lineBytes.decodeToString()
            val ts = runCatching {
                lenientJson.parseToJsonElement(text).jsonObject["ts"]?.jsonPrimitive?.content
            }.getOrNull() ?: return@mapNotNull null
            val epochMs = parseRfc3339ToEpochMillis(ts) ?: return@mapNotNull null
            if (epochMs in fromEpochMs..toEpochMs) text else null
        }

    private companion object {
        // RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        const val IMPORTANCE_FOREGROUND_SERVICE = 125
        const val MAX_TRACE_BYTES = 2 * 1024 * 1024
        const val BREADCRUMB_WINDOW_MS = 30L * 60 * 1000
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
