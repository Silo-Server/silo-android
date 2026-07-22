package org.siloserver.silo.common.diagnostics.consent

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import java.io.File
import java.util.UUID

/**
 * On-disk pending diagnostics reports: one directory per report, published
 * atomically (staged into `.staging-<id>/`, then renamed), so a scan never
 * observes a half-written report. Artifact files live at their archive-relative
 * names (`device.json`, `logs.jsonl`, `crash/stack.txt`, …) so the bundle
 * builder reads them by walking the allowlist — no remapping table to drift.
 *
 * Lifecycle rules (each proven load-bearing by the Apple implementation's
 * review rounds — do not "simplify" the ordering):
 *  - 7-day expiry, evaluated lazily on save/list — visible, not timer-based.
 *  - Cap of 3 per binding, oldest evicted; a capture evicted *immediately* on
 *    save returns null and must NOT be marked fingerprint-seen by callers,
 *    or that crash becomes silently unreportable forever.
 *  - The seen-fingerprint set is GLOBAL (a crash physically happened,
 *    independent of which account was signed in); the auto-upload throttle is
 *    per-(binding, fingerprint); the Retry-After deadline is per-binding.
 */
class PendingReportStore(
    baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pendingDir = File(baseDir, "pending")
    private val metaDir = File(baseDir, "fingerprints")
    private val lock = Any()

    @Serializable
    data class PendingReportBinding(
        val binding: DiagnosticsBinding,
        val profileId: String? = null,
        val capturedAtEpochMs: Long,
        val type: DiagnosticsReportType,
        val fingerprint: String,
    )

    @Serializable
    data class PendingReportState(
        val needsServerUpdate: Boolean = false,
        val tooLarge: Boolean = false,
        val promptDeclined: Boolean = false,
    ) {
        val isPermanentFailure: Boolean get() = needsServerUpdate || tooLarge
    }

    data class PendingReport(
        val id: String,
        val directory: File,
        val binding: PendingReportBinding,
        val manifestDraft: DiagnosticsManifestDraft,
        val state: PendingReportState,
    ) {
        fun isExpired(nowEpochMs: Long): Boolean =
            nowEpochMs - binding.capturedAtEpochMs > EXPIRY_MS

        fun expiresAtEpochMs(): Long = binding.capturedAtEpochMs + EXPIRY_MS
    }

    class Artifact(val name: String, val bytes: ByteArray)

    class PendingReportCapture(
        val binding: DiagnosticsBinding,
        val profileId: String?,
        val capturedAtEpochMs: Long,
        val type: DiagnosticsReportType,
        val fingerprint: String,
        val manifestDraft: DiagnosticsManifestDraft,
        val artifacts: List<Artifact>,
    )

    /**
     * Persists a capture. Returns null when the capture was immediately
     * evicted by the per-binding cap (it was older than everything retained) —
     * callers must skip fingerprint marking in that case.
     */
    fun save(capture: PendingReportCapture): PendingReport? = synchronized(lock) {
        cleanupExpiredLocked()
        val id = UUID.randomUUID().toString()
        val staging = File(pendingDir, ".staging-$id")
        val final = File(pendingDir, id)
        try {
            staging.mkdirs()
            writeAtomic(
                File(staging, "binding.json"),
                json.encodeToString(
                    PendingReportBinding.serializer(),
                    PendingReportBinding(
                        binding = capture.binding,
                        profileId = capture.profileId,
                        capturedAtEpochMs = capture.capturedAtEpochMs,
                        type = capture.type,
                        fingerprint = capture.fingerprint,
                    ),
                ),
            )
            writeAtomic(
                File(staging, "manifest.json"),
                json.encodeToString(DiagnosticsManifestDraft.serializer(), capture.manifestDraft),
            )
            writeAtomic(File(staging, "state.json"), json.encodeToString(PendingReportState.serializer(), PendingReportState()))
            for (artifact in capture.artifacts) {
                val target = resolveArtifact(staging, artifact.name)
                    ?: throw IllegalArgumentException("artifact name outside allowlist: ${artifact.name}")
                target.parentFile?.mkdirs()
                target.writeBytes(artifact.bytes)
            }
            if (!staging.renameTo(final)) throw IllegalStateException("publish rename failed for $id")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            Log.w(TAG, "pending-report save failed", t)
            return@synchronized null
        }
        enforceCapLocked(capture.binding)
        // Reload: the cap pass may have evicted this very report.
        loadReportLocked(final)
    }

    /** All non-corrupt pending reports, oldest first; expired ones are deleted. */
    fun listReports(binding: DiagnosticsBinding? = null): List<PendingReport> = synchronized(lock) {
        cleanupExpiredLocked()
        allReportsLocked()
            .filter { binding == null || it.binding.binding == binding }
            .sortedBy { it.binding.capturedAtEpochMs }
    }

    fun delete(report: PendingReport) = synchronized(lock) {
        report.directory.deleteRecursively()
    }

    fun purge(binding: DiagnosticsBinding) = synchronized(lock) {
        allReportsLocked().filter { it.binding.binding == binding }.forEach { it.directory.deleteRecursively() }
        // Per-binding throttle + retry-after go with the binding; the global
        // seen-fingerprint set deliberately does not.
        writeThrottle(readThrottle().filterKeys { !it.startsWith("${binding.storageKey}|") })
        writeRetryAfter(readRetryAfter() - binding.storageKey)
    }

    fun purgeServer(serverInstanceId: String) = synchronized(lock) {
        allReportsLocked().filter { it.binding.binding.serverInstanceId == serverInstanceId }
            .forEach { it.directory.deleteRecursively() }
        val prefix = "$serverInstanceId|"
        writeThrottle(readThrottle().filterKeys { !it.startsWith(prefix) })
        writeRetryAfter(readRetryAfter().filterKeys { !it.startsWith(prefix) })
    }

    fun readArtifact(report: PendingReport, name: String): ByteArray? {
        val file = resolveArtifact(report.directory, name) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    // ---- state transitions ----------------------------------------------

    fun markNeedsServerUpdate(report: PendingReport): PendingReport =
        updateState(report) { it.copy(needsServerUpdate = true) }

    fun markTooLarge(report: PendingReport): PendingReport =
        updateState(report) { it.copy(tooLarge = true) }

    fun markPromptDeclined(report: PendingReport): PendingReport =
        updateState(report) { it.copy(promptDeclined = true) }

    private fun updateState(report: PendingReport, transform: (PendingReportState) -> PendingReportState): PendingReport =
        synchronized(lock) {
            val updated = transform(report.state)
            writeAtomic(File(report.directory, "state.json"), json.encodeToString(PendingReportState.serializer(), updated))
            report.copy(state = updated)
        }

    /**
     * Rewrites the frozen manifest's consent to the *current* mode + notice
     * version before an upload attempt. Without this, a report captured under
     * a since-demoted Always election would claim stale consent forever and
     * the server would bounce it even after the user re-consents.
     */
    fun updatingConsent(
        report: PendingReport,
        mode: DiagnosticsConsentMode,
        noticeVersion: Int,
    ): PendingReport = synchronized(lock) {
        val current = report.manifestDraft.consent
        if (current.mode == mode && current.noticeVersion == noticeVersion) return@synchronized report
        val updated = report.manifestDraft.copy(
            consent = DiagnosticsManifest.Consent(mode = mode, noticeVersion = noticeVersion),
        )
        writeAtomic(File(report.directory, "manifest.json"), json.encodeToString(DiagnosticsManifestDraft.serializer(), updated))
        report.copy(manifestDraft = updated)
    }

    // ---- fingerprints / throttle / retry-after ---------------------------

    fun hasSeenFingerprint(fingerprint: String): Boolean = synchronized(lock) {
        pruneFingerprintsLocked()
        readSeenFingerprints().containsKey(fingerprint)
    }

    fun markFingerprintSeen(fingerprint: String) = synchronized(lock) {
        writeSeenFingerprints(readSeenFingerprints() + (fingerprint to clock()))
    }

    fun canAutoUpload(binding: DiagnosticsBinding, fingerprint: String): Boolean = synchronized(lock) {
        val last = readThrottle()["${binding.storageKey}|$fingerprint"] ?: return@synchronized true
        clock() - last >= AUTO_UPLOAD_THROTTLE_MS
    }

    fun recordAutoUploadAttempt(binding: DiagnosticsBinding, fingerprint: String) = synchronized(lock) {
        writeThrottle(readThrottle() + ("${binding.storageKey}|$fingerprint" to clock()))
    }

    /** Server-directed backoff (429 quota_exceeded / 503 busy), per binding. */
    fun retryAfterDeadlineEpochMs(binding: DiagnosticsBinding): Long? = synchronized(lock) {
        readRetryAfter()[binding.storageKey]?.takeIf { it > clock() }
    }

    fun setRetryAfterDeadline(binding: DiagnosticsBinding, deadlineEpochMs: Long) = synchronized(lock) {
        writeRetryAfter(readRetryAfter() + (binding.storageKey to deadlineEpochMs))
    }

    fun clearRetryAfterDeadline(binding: DiagnosticsBinding) = synchronized(lock) {
        writeRetryAfter(readRetryAfter() - binding.storageKey)
    }

    // ---- internals -------------------------------------------------------

    private fun allReportsLocked(): List<PendingReport> {
        val dirs = pendingDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".staging-") } ?: return emptyList()
        val out = ArrayList<PendingReport>(dirs.size)
        for (dir in dirs) {
            val report = loadReportLocked(dir)
            if (report == null) {
                // Corrupt/incomplete (should be prevented by staged publish;
                // defensive): remove so it can't poison future scans.
                dir.deleteRecursively()
            } else {
                out.add(report)
            }
        }
        // Leftover staging dirs from a killed process are garbage by definition.
        pendingDir.listFiles { f -> f.isDirectory && f.name.startsWith(".staging-") }
            ?.forEach { it.deleteRecursively() }
        return out
    }

    private fun loadReportLocked(dir: File): PendingReport? {
        if (!dir.isDirectory) return null
        return runCatching {
            val binding = json.decodeFromString(
                PendingReportBinding.serializer(),
                File(dir, "binding.json").readText(),
            )
            val manifest = json.decodeFromString(
                DiagnosticsManifestDraft.serializer(),
                File(dir, "manifest.json").readText(),
            )
            val state = runCatching {
                json.decodeFromString(PendingReportState.serializer(), File(dir, "state.json").readText())
            }.getOrDefault(PendingReportState())
            PendingReport(id = dir.name, directory = dir, binding = binding, manifestDraft = manifest, state = state)
        }.getOrNull()
    }

    private fun cleanupExpiredLocked() {
        val now = clock()
        allReportsLocked().filter { it.isExpired(now) }.forEach { it.directory.deleteRecursively() }
    }

    private fun enforceCapLocked(binding: DiagnosticsBinding) {
        val forBinding = allReportsLocked()
            .filter { it.binding.binding == binding }
            .sortedBy { it.binding.capturedAtEpochMs }
        if (forBinding.size > MAX_PENDING_PER_BINDING) {
            forBinding.take(forBinding.size - MAX_PENDING_PER_BINDING)
                .forEach { it.directory.deleteRecursively() }
        }
    }

    private fun pruneFingerprintsLocked() {
        val cutoff = clock() - FINGERPRINT_RETENTION_MS
        val current = readSeenFingerprints()
        val pruned = current.filterValues { it >= cutoff }
        if (pruned.size != current.size) writeSeenFingerprints(pruned)
    }

    /** Archive-relative artifact names only; never manifest/binding/state, never traversal. */
    private fun resolveArtifact(dir: File, name: String): File? {
        if (name !in ALLOWED_ARTIFACT_NAMES) return null
        val target = File(dir, name)
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return null
        return target
    }

    private fun readSeenFingerprints(): Map<String, Long> = readLongMap(File(metaDir, "seen-fingerprints.json"))
    private fun writeSeenFingerprints(value: Map<String, Long>) = writeLongMap(File(metaDir, "seen-fingerprints.json"), value)
    private fun readThrottle(): Map<String, Long> = readLongMap(File(metaDir, "auto-upload-throttle.json"))
    private fun writeThrottle(value: Map<String, Long>) = writeLongMap(File(metaDir, "auto-upload-throttle.json"), value)
    private fun readRetryAfter(): Map<String, Long> = readLongMap(File(metaDir, "retry-after.json"))
    private fun writeRetryAfter(value: Map<String, Long>) = writeLongMap(File(metaDir, "retry-after.json"), value)

    private fun readLongMap(file: File): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), file.readText())
        }.onFailure { Log.w(TAG, "read failed for ${file.name}", it) }.getOrDefault(emptyMap())
    }

    private fun writeLongMap(file: File, value: Map<String, Long>) {
        writeAtomic(file, json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), value))
    }

    private fun writeAtomic(target: File, text: String) =
        org.siloserver.silo.common.diagnostics.writeDiagnosticsFileAtomic(target, text, TAG)

    companion object {
        private const val TAG = "PendingReportStore"
        private val json = Json { ignoreUnknownKeys = true }
        const val MAX_PENDING_PER_BINDING = 3
        const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
        const val AUTO_UPLOAD_THROTTLE_MS = 24L * 60 * 60 * 1000
        const val FINGERPRINT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** Artifact files a capture may attach (everything the builder reads from disk). */
        val ALLOWED_ARTIFACT_NAMES: Set<String> = setOf(
            "device.json",
            "logs.jsonl",
            "breadcrumbs.jsonl",
            "crash/summary.json",
            "crash/stack.txt",
            "crash/tombstone.pb",
        )
    }
}
