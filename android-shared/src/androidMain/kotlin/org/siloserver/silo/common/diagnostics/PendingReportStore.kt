package org.siloserver.silo.common.diagnostics

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.decodeDiagnosticsManifest
import org.siloserver.silo.model.diagnostics.validate

@Serializable
data class PendingReportBinding(
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("account_user_id") val accountUserId: String,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("ownership_generation") val ownershipGeneration: Long,
    @SerialName("destination_kind") val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
) {
    val binding: DiagnosticsBinding get() = DiagnosticsBinding(serverInstanceId, accountUserId)

    // ownershipGeneration and profileId describe capture-time attribution. Persisted ownership
    // is deliberately account-scoped so another eligible adult profile on the same account can
    // review and send a retained report using its captured X-Profile-Id.
    fun matches(context: DiagnosticsCaptureContext): Boolean =
        serverInstanceId == context.binding.serverInstanceId &&
            accountUserId == context.binding.accountUserId
}

@Serializable
enum class PendingReportStatus { PENDING, RETRYABLE, PERMANENT_FAILURE }

@Serializable
data class PendingReportState(
    val status: PendingReportStatus = PendingReportStatus.PENDING,
    @SerialName("captured_at_epoch_ms") val capturedAtEpochMs: Long,
    val fingerprint: String,
    @SerialName("attempt_count") val attemptCount: Int = 0,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @SerialName("hosted_envelope_generation") val hostedEnvelopeGeneration: String? = null,
    @SerialName("hosted_consent_refresh_required") val hostedConsentRefreshRequired: Boolean = false,
    @SerialName("hosted_remote_short_id") val hostedRemoteShortId: String? = null,
)

data class PendingReportCapture(
    val binding: PendingReportBinding,
    val manifest: DiagnosticsManifest,
    val artifacts: Map<String, ByteArray>,
    val fingerprint: String,
    val capturedAtEpochMs: Long,
)

data class PendingReport(
    val id: String,
    val directory: File,
    val binding: PendingReportBinding,
    val manifest: DiagnosticsManifest,
    val state: PendingReportState,
)

class PendingReportRejectedException(message: String) : IllegalStateException(message)

sealed interface HostedEnvelopeLoadResult {
    data object Missing : HostedEnvelopeLoadResult
    data class Available(val bundle: DiagnosticsBundle) : HostedEnvelopeLoadResult
    data object Corrupt : HostedEnvelopeLoadResult
}

interface PendingReportStore {
    fun save(capture: PendingReportCapture): PendingReport
    fun list(binding: DiagnosticsBinding): List<PendingReport>
    fun load(id: String): PendingReport?
    fun delete(id: String)
    fun stageHostedDeletionAndDelete(id: String)
    fun purge(binding: DiagnosticsBinding)
    fun recordHostedReadyAndDelete(id: String, binding: PendingReportBinding)
    fun hostedReadyBinding(id: String): DiagnosticsBinding?
    fun hostedDeletionIntents(): List<String>
    fun completeHostedDeletion(id: String)
    fun markState(id: String, status: PendingReportStatus, errorCode: String? = null)
    fun hasSeenFingerprint(fingerprint: String): Boolean
    fun markThrottled(key: String, atEpochMs: Long)
    fun isThrottled(key: String, windowMs: Long): Boolean
    fun retryAfterDeadline(binding: DiagnosticsBinding): Long?
    fun setRetryAfterDeadline(binding: DiagnosticsBinding, deadlineEpochMs: Long)
    fun clearRetryAfterDeadline(binding: DiagnosticsBinding)
    fun loadHostedEnvelope(id: String): HostedEnvelopeLoadResult
    fun saveHostedEnvelope(id: String, bundle: DiagnosticsBundle)
    fun markHostedConsentRefreshRequired(id: String)
    fun markHostedProcessing(id: String, shortId: String)
}

class FilePendingReportStore(
    noBackupFilesDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxReportsPerBinding: Int = DEFAULT_MAX_REPORTS,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : PendingReportStore {
    private val root = noBackupFilesDir.resolve("client-diagnostics/pending")
    private val indexFile = noBackupFilesDir.resolve("client-diagnostics/pending-index.json")
    private val hostedDeletionIntentsFile =
        noBackupFilesDir.resolve("client-diagnostics/hosted-deletion-intents.json")
    private val hostedReadyReceiptsFile =
        noBackupFilesDir.resolve("client-diagnostics/hosted-ready-receipts.json")
    private val lock = Any()

    init {
        require(maxReportsPerBinding > 0)
        require(retentionMs > 0)
        // A process can stop after publishing either a READY receipt or a
        // hosted erasure intent but before removing the corresponding report
        // directory. Finish that local half before serving any data.
        synchronized(lock) {
            runCatching { reconcileHostedReadyReceiptsLocked() }
            runCatching { reconcileHostedDeletionIntentsLocked() }
        }
    }

    override fun save(capture: PendingReportCapture): PendingReport = synchronized(lock) {
        validateCapture(capture)
        if (capture.capturedAtEpochMs < nowMs() - retentionMs) {
            throw PendingReportRejectedException("capture is outside the retention window")
        }
        pruneLocked()
        val sameBinding = reportsLocked().filter { it.binding.binding == capture.binding.binding }
        val oldest = sameBinding.minByOrNull { it.state.capturedAtEpochMs }
        if (sameBinding.size >= maxReportsPerBinding && oldest != null && capture.capturedAtEpochMs <= oldest.state.capturedAtEpochMs) {
            throw PendingReportRejectedException("capture is older than retained reports")
        }

        ensureRoot()
        val id = idFactory().lowercase()
        require(ID_PATTERN.matches(id)) { "invalid pending report id" }
        val staging = root.resolve(".staging-$id")
        val published = root.resolve(id)
        check(!staging.exists() && !published.exists()) { "pending report id collision" }
        val state = PendingReportState(
            capturedAtEpochMs = capture.capturedAtEpochMs,
            fingerprint = capture.fingerprint,
            updatedAtEpochMs = nowMs(),
        )
        var indexBeforePublish: PendingIndex? = null
        try {
            check(staging.mkdirs()) { "unable to create report staging directory" }
            syncDirectory(root)
            writeSynced(staging.resolve(BINDING_FILE), JSON.encodeToString(capture.binding).encodeToByteArray())
            writeSynced(staging.resolve(MANIFEST_FILE), JSON.encodeToString(capture.manifest).encodeToByteArray())
            writeSynced(staging.resolve(STATE_FILE), JSON.encodeToString(state).encodeToByteArray())
            capture.artifacts.forEach { (path, bytes) ->
                val target = staging.resolve(path)
                val parent = checkNotNull(target.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                writeSynced(target, bytes)
            }
            syncDirectory(staging)
            atomicRename(staging, published)
            syncDirectory(root)

            val currentIndex = readIndex().pruned(nowMs(), retentionMs)
            indexBeforePublish = currentIndex
            val index = currentIndex.copy(
                fingerprints = currentIndex.fingerprints + (capture.fingerprint to capture.capturedAtEpochMs),
            )
            writeIndex(index)
            if (sameBinding.size >= maxReportsPerBinding) oldest?.let { deleteDirectory(it.directory) }
            loadLocked(id) ?: error("published pending report failed validation")
        } catch (error: Throwable) {
            runCatching { staging.deleteRecursively() }
            runCatching { published.deleteRecursively() }
            runCatching { syncDirectory(root) }
            indexBeforePublish?.let { previous -> runCatching { writeIndex(previous) } }
            throw error
        }
    }

    override fun list(binding: DiagnosticsBinding): List<PendingReport> = synchronized(lock) {
        pruneLocked()
        reportsLocked()
            .filter { it.binding.binding == binding }
            .sortedByDescending { it.state.capturedAtEpochMs }
    }

    override fun load(id: String): PendingReport? = synchronized(lock) {
        if (!ID_PATTERN.matches(id)) return@synchronized null
        pruneLocked()
        loadLocked(id)
    }

    override fun delete(id: String) = synchronized(lock) {
        if (ID_PATTERN.matches(id)) deleteDirectory(root.resolve(id))
    }

    override fun stageHostedDeletionAndDelete(id: String) = synchronized(lock) {
        if (!ID_PATTERN.matches(id)) return@synchronized
        pruneHostedReadyReceiptsLocked()
        val report = loadReportDirectoryLocked(id)
        val receiptId = id.takeIf { it in readHostedReadyReceiptsLocked() }
        if (report == null && receiptId == null) return@synchronized
        stageHostedDeletionsLocked(listOfNotNull(report), setOfNotNull(receiptId))
        deleteDirectory(root.resolve(id))
    }

    override fun purge(binding: DiagnosticsBinding) = synchronized(lock) {
        pruneHostedReadyReceiptsLocked()
        val removed = reportDirectoriesLocked().filter { it.binding.binding == binding }
        val receiptIds = readHostedReadyReceiptsLocked()
            .filterValues { it.binding == binding }
            .keys
        stageHostedDeletionsLocked(removed, receiptIds)
        removed.forEach { deleteDirectory(it.directory) }
        val removedFingerprints = removed.mapTo(hashSetOf()) { it.state.fingerprint }
        val index = readIndex()
        writeIndex(
            index.copy(
                fingerprints = index.fingerprints - removedFingerprints,
                retryAfter = index.retryAfter - binding.scopeKey(),
            ),
        )
    }

    override fun recordHostedReadyAndDelete(id: String, binding: PendingReportBinding) = synchronized(lock) {
        require(ID_PATTERN.matches(id)) { "invalid hosted report id" }
        require(binding.destinationKind == DiagnosticsDestinationKind.HOSTED)
        require(binding.serverInstanceId == HOSTED_DIAGNOSTICS_COLLECTOR_ID)
        pruneHostedReadyReceiptsLocked()
        loadReportDirectoryLocked(id)?.let { report ->
            require(report.binding == binding) { "hosted READY binding changed" }
        }
        if (id !in readHostedDeletionIntentsLocked()) {
            val receipts = readHostedReadyReceiptsLocked().toMutableMap()
            receipts[id] = HostedReadyReceipt(
                binding = binding.binding,
                destinationKind = binding.destinationKind,
                readyAtEpochMs = nowMs(),
            )
            // Publish the UUID/binding receipt before deleting raw evidence so
            // a queued Delete or Turn Off can still request remote erasure.
            writeHostedReadyReceiptsLocked(receipts)
        }
        deleteDirectory(root.resolve(id))
    }

    override fun hostedReadyBinding(id: String): DiagnosticsBinding? = synchronized(lock) {
        if (!ID_PATTERN.matches(id)) return@synchronized null
        pruneHostedReadyReceiptsLocked()
        readHostedReadyReceiptsLocked()[id]?.binding
    }

    override fun hostedDeletionIntents(): List<String> = synchronized(lock) {
        runCatching { reconcileHostedDeletionIntentsLocked() }.getOrDefault(emptyList())
    }

    override fun completeHostedDeletion(id: String) = synchronized(lock) {
        if (!ID_PATTERN.matches(id)) return@synchronized
        val intents = readHostedDeletionIntentsLocked()
        if (id !in intents) return@synchronized
        check(!root.resolve(id).exists()) { "hosted report evidence still exists" }
        val receipts = readHostedReadyReceiptsLocked()
        if (id in receipts) writeHostedReadyReceiptsLocked(receipts - id)
        writeHostedDeletionIntentsLocked(intents - id)
    }

    override fun markState(id: String, status: PendingReportStatus, errorCode: String?) = synchronized(lock) {
        val report = loadLocked(id) ?: return@synchronized
        val updated = report.state.copy(
            status = status,
            attemptCount = report.state.attemptCount + 1,
            errorCode = errorCode,
            updatedAtEpochMs = nowMs(),
        )
        writeAtomic(report.directory.resolve(STATE_FILE), JSON.encodeToString(updated).encodeToByteArray())
    }

    override fun hasSeenFingerprint(fingerprint: String): Boolean = synchronized(lock) {
        pruneLocked()
        readIndex().fingerprints.containsKey(fingerprint)
    }

    override fun markThrottled(key: String, atEpochMs: Long) = synchronized(lock) {
        require(key.isNotBlank())
        val index = readIndex().pruned(nowMs(), retentionMs)
        writeIndex(index.copy(throttles = index.throttles + (key to atEpochMs)))
    }

    override fun isThrottled(key: String, windowMs: Long): Boolean = synchronized(lock) {
        require(windowMs > 0)
        pruneLocked()
        readIndex().throttles[key]?.let { nowMs() - it <= windowMs } == true
    }

    override fun retryAfterDeadline(binding: DiagnosticsBinding): Long? = synchronized(lock) {
        readIndex().retryAfter[binding.scopeKey()]?.takeIf { it > nowMs() }
    }

    override fun setRetryAfterDeadline(binding: DiagnosticsBinding, deadlineEpochMs: Long) = synchronized(lock) {
        val index = readIndex().pruned(nowMs(), retentionMs)
        val scopeKey = binding.scopeKey()
        val deadline = maxOf(index.retryAfter[scopeKey] ?: 0L, deadlineEpochMs)
        writeIndex(index.copy(retryAfter = index.retryAfter + (scopeKey to deadline)))
    }

    override fun clearRetryAfterDeadline(binding: DiagnosticsBinding) = synchronized(lock) {
        val index = readIndex().pruned(nowMs(), retentionMs)
        writeIndex(index.copy(retryAfter = index.retryAfter - binding.scopeKey()))
    }

    override fun loadHostedEnvelope(id: String): HostedEnvelopeLoadResult = synchronized(lock) {
        val report = loadLocked(id) ?: return@synchronized HostedEnvelopeLoadResult.Corrupt
        val generation = report.state.hostedEnvelopeGeneration
        if (generation != null) {
            if (!ID_PATTERN.matches(generation)) return@synchronized HostedEnvelopeLoadResult.Corrupt
            val directory = report.directory.resolve("$HOSTED_ENVELOPE_PREFIX$generation")
            return@synchronized readHostedEnvelope(report, directory)
                ?.let(HostedEnvelopeLoadResult::Available)
                ?: HostedEnvelopeLoadResult.Corrupt
        }

        report.directory.listFiles().orEmpty()
            .filter { it.name.startsWith(HOSTED_ENVELOPE_STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
        val recoverable = report.directory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(HOSTED_ENVELOPE_PREFIX) }
            .sortedByDescending(File::lastModified)
        for (directory in recoverable) {
            val recoveredGeneration = directory.name.removePrefix(HOSTED_ENVELOPE_PREFIX)
            if (!ID_PATTERN.matches(recoveredGeneration)) continue
            val bundle = readHostedEnvelope(report, directory) ?: continue
            val state = report.state.copy(hostedEnvelopeGeneration = recoveredGeneration)
            writeAtomic(report.directory.resolve(STATE_FILE), JSON.encodeToString(state).encodeToByteArray())
            return@synchronized HostedEnvelopeLoadResult.Available(bundle)
        }
        if (recoverable.isNotEmpty()) {
            HostedEnvelopeLoadResult.Corrupt
        } else {
            HostedEnvelopeLoadResult.Missing
        }
    }

    override fun saveHostedEnvelope(id: String, bundle: DiagnosticsBundle) = synchronized(lock) {
        val report = checkNotNull(loadLocked(id)) { "pending report is unavailable" }
        validateHostedEnvelope(report, bundle)
        val generation = UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)
        val staging = report.directory.resolve("$HOSTED_ENVELOPE_STAGING_PREFIX$generation")
        val published = report.directory.resolve("$HOSTED_ENVELOPE_PREFIX$generation")
        check(!staging.exists() && !published.exists()) { "hosted envelope generation collision" }
        try {
            check(staging.mkdirs()) { "unable to create hosted envelope staging directory" }
            writeSynced(staging.resolve(HOSTED_MANIFEST_FILE), bundle.manifestBytes)
            writeSynced(staging.resolve(HOSTED_BUNDLE_FILE), bundle.bytes)
            bundle.manifest.archive.entries.forEach { path ->
                val bytes = checkNotNull(bundle.sanitizedEntries[path]) {
                    "missing sanitized hosted member: $path"
                }
                val target = staging.resolve(HOSTED_ENTRIES_DIRECTORY).resolve(path)
                check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                writeSynced(target, bytes)
            }
            syncDirectory(staging)
            atomicRename(staging, published)
            syncDirectory(report.directory)
            val state = report.state.copy(
                hostedEnvelopeGeneration = generation,
                hostedConsentRefreshRequired = false,
                updatedAtEpochMs = nowMs(),
            )
            writeAtomic(report.directory.resolve(STATE_FILE), JSON.encodeToString(state).encodeToByteArray())
            report.directory.listFiles().orEmpty()
                .filter {
                    it.isDirectory &&
                        it.name.startsWith(HOSTED_ENVELOPE_PREFIX) &&
                        it.name != published.name
                }
                .forEach(File::deleteRecursively)
        } catch (error: Throwable) {
            runCatching { staging.deleteRecursively() }
            throw error
        }
    }

    override fun markHostedConsentRefreshRequired(id: String) = synchronized(lock) {
        val report = loadLocked(id) ?: return@synchronized
        val updated = report.state.copy(
            hostedConsentRefreshRequired = true,
            updatedAtEpochMs = nowMs(),
        )
        writeAtomic(report.directory.resolve(STATE_FILE), JSON.encodeToString(updated).encodeToByteArray())
    }

    override fun markHostedProcessing(id: String, shortId: String) = synchronized(lock) {
        require(shortId.isNotBlank())
        val report = loadLocked(id) ?: return@synchronized
        val updated = report.state.copy(
            status = PendingReportStatus.RETRYABLE,
            errorCode = "processing",
            hostedRemoteShortId = shortId,
            updatedAtEpochMs = nowMs(),
        )
        writeAtomic(report.directory.resolve(STATE_FILE), JSON.encodeToString(updated).encodeToByteArray())
    }

    private fun readHostedEnvelope(report: PendingReport, directory: File): DiagnosticsBundle? = runCatching {
        require(directory.isDirectory)
        val manifestBytes = directory.resolve(HOSTED_MANIFEST_FILE).readBytes()
        val manifest = decodeDiagnosticsManifest(manifestBytes.decodeToString()).also(DiagnosticsManifest::validate)
        val bundleBytes = directory.resolve(HOSTED_BUNDLE_FILE).readBytes()
        val entriesRoot = directory.resolve(HOSTED_ENTRIES_DIRECTORY)
        val entries = manifest.archive.entries.associateWith { path ->
            val entry = entriesRoot.resolve(path)
            require(entry.isFile && entry.isWithinDirectory(entriesRoot))
            entry.readBytes()
        }
        val bundle = DiagnosticsBundle(manifest, manifestBytes, bundleBytes, entries)
        validateHostedEnvelope(report, bundle)
        bundle
    }.getOrNull()

    private fun validateHostedEnvelope(report: PendingReport, bundle: DiagnosticsBundle) {
        bundle.manifest.validate()
        require(report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED)
        require(bundle.manifest.destination.serverInstanceId == HOSTED_DIAGNOSTICS_COLLECTOR_ID)
        require(bundle.manifest.report.profileId == null)
        require(bundle.manifest.playbackSessionIds.isEmpty())
        require("crash/tombstone.pb" !in bundle.manifest.archive.entries)
        require(bundle.manifest.archive.entries == bundle.sanitizedEntries.keys.toList())
        require(bundle.manifest.archive.bytes == bundle.bytes.size.toLong())
        require(bundle.manifest.archive.sha256 == sha256Hex(bundle.bytes))
        require(bundle.sanitizedEntries[MANIFEST_FILE] != null)
        require(bundle.sanitizedEntries[DEVICE_FILE] != null)
        val reconstructed = FileDiagnosticsBundleBuilder().reframeHosted(bundle, bundle.manifest.consent)
        require(reconstructed.manifestBytes.contentEquals(bundle.manifestBytes))
        require(reconstructed.bytes.contentEquals(bundle.bytes))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun validateCapture(capture: PendingReportCapture) {
        require(capture.binding.serverInstanceId == capture.manifest.destination.serverInstanceId)
        require(capture.binding.profileId == capture.manifest.report.profileId)
        require(capture.fingerprint.isNotBlank() && capture.fingerprint.encodeToByteArray().size <= 256)
        require(capture.capturedAtEpochMs >= 0)
        require(DEVICE_FILE in capture.artifacts) { "device.json is required" }
        var totalBytes = 0L
        capture.artifacts.forEach { (path, bytes) ->
            require(path in ALLOWED_ARTIFACTS) { "unsupported diagnostics artifact: $path" }
            require(!path.startsWith('/') && ".." !in path.split('/')) { "unsafe diagnostics artifact: $path" }
            totalBytes += bytes.size
        }
        require(totalBytes <= MAX_CAPTURE_BYTES) { "pending capture exceeds byte limit" }
    }

    private fun pruneLocked() {
        pruneHostedReadyReceiptsLocked()
        if (!root.exists()) {
            val index = readIndex()
            val pruned = index.pruned(nowMs(), retentionMs)
            if (pruned != index) writeIndex(pruned)
            return
        }
        root.listFiles().orEmpty().filter { it.name.startsWith(".staging-") }.forEach(File::deleteRecursively)
        val cutoff = nowMs() - retentionMs
        reportsLocked().filter { it.state.capturedAtEpochMs < cutoff }.forEach { deleteDirectory(it.directory) }
        val index = readIndex()
        val pruned = index.pruned(nowMs(), retentionMs)
        if (pruned != index) writeIndex(pruned)
    }

    private fun reportsLocked(): List<PendingReport> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && ID_PATTERN.matches(it.name) }
            .mapNotNull { loadLocked(it.name) }

    private fun loadLocked(id: String): PendingReport? {
        if (id in hostedDeletionIntentIdsLocked() || id in hostedReadyReceiptIdsLocked()) {
            // Never let evidence covered by a READY receipt or durable erasure
            // request reappear or reach an uploader, even if physical cleanup
            // must be retried after an interrupted deletion.
            runCatching { deleteDirectory(root.resolve(id)) }
            return null
        }
        return loadReportDirectoryLocked(id)
    }

    private fun reportDirectoriesLocked(): List<PendingReport> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && ID_PATTERN.matches(it.name) }
            .mapNotNull { loadReportDirectoryLocked(it.name) }

    private fun loadReportDirectoryLocked(id: String): PendingReport? {
        val directory = root.resolve(id)
        if (!directory.isDirectory) return null
        return runCatching {
            val binding = JSON.decodeFromString<PendingReportBinding>(directory.resolve(BINDING_FILE).readText())
            val manifest = decodeDiagnosticsManifest(directory.resolve(MANIFEST_FILE).readText())
            val state = JSON.decodeFromString<PendingReportState>(directory.resolve(STATE_FILE).readText())
            require(directory.resolve(DEVICE_FILE).isFile)
            require(binding.serverInstanceId == manifest.destination.serverInstanceId)
            require(binding.profileId == manifest.report.profileId)
            PendingReport(id, directory, binding, manifest, state)
        }.getOrNull()
    }

    private fun ensureRoot() {
        check(root.mkdirs() || root.isDirectory) { "unable to create pending report root" }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        file.parentFile?.let(::syncDirectory)
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temporary = file.resolveSibling("${file.name}.tmp")
        writeSynced(temporary, bytes)
        atomicRename(temporary, file)
        file.parentFile?.let(::syncDirectory)
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists()) {
            check(directory.deleteRecursively()) { "unable to delete ${directory.name}" }
            directory.parentFile?.let(::syncDirectory)
        }
    }

    private fun atomicRename(source: File, target: File) {
        val renamedByOs = runCatching {
            Os.rename(source.absolutePath, target.absolutePath)
            !source.exists() && target.exists()
        }.getOrDefault(false)
        if (!renamedByOs) {
            if (target.exists()) check(target.delete()) { "unable to replace ${target.name}" }
            check(source.renameTo(target)) { "unable to atomically publish ${target.name}" }
        }
        check(!source.exists() && target.exists()) { "atomic publish did not complete for ${target.name}" }
    }

    private fun syncDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        runCatching {
            descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            Os.fsync(checkNotNull(descriptor))
        }
        runCatching { descriptor?.let(Os::close) }
    }

    private fun readIndex(): PendingIndex {
        if (!indexFile.isFile || indexFile.length() > MAX_INDEX_BYTES) return PendingIndex()
        return runCatching { JSON.decodeFromString<PendingIndex>(indexFile.readText()) }.getOrDefault(PendingIndex())
    }

    private fun writeIndex(index: PendingIndex) {
        indexFile.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val bytes = JSON.encodeToString(index).encodeToByteArray()
        check(bytes.size <= MAX_INDEX_BYTES)
        writeAtomic(indexFile, bytes)
    }

    private fun stageHostedDeletionsLocked(
        reports: List<PendingReport>,
        readyReceiptIds: Set<String> = emptySet(),
    ) {
        val reportIds = reports.asSequence()
            .filter { report ->
                report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED &&
                    (
                        report.state.hostedEnvelopeGeneration != null ||
                            report.state.hostedRemoteShortId != null
                    )
            }
            .map(PendingReport::id)
            .toSet() + readyReceiptIds
        if (reportIds.isEmpty()) return
        val intents = readHostedDeletionIntentsLocked().toMutableMap()
        reportIds.forEach { reportId -> intents[reportId] = nowMs() }
        // Publish the UUID-only erasure intent before deleting any evidence.
        // If persistence fails, the caller keeps every report intact.
        writeHostedDeletionIntentsLocked(intents)
    }

    private fun reconcileHostedDeletionIntentsLocked(): List<String> {
        val reportIds = readHostedDeletionIntentsLocked().keys.sorted()
        return reportIds.filter { reportId ->
            val directory = root.resolve(reportId)
            if (!directory.exists()) {
                true
            } else {
                runCatching {
                    deleteDirectory(directory)
                    true
                }.getOrDefault(false)
            }
        }
    }

    private fun hostedDeletionIntentIdsLocked(): Set<String> =
        runCatching { readHostedDeletionIntentsLocked().keys }.getOrDefault(emptySet())

    private fun reconcileHostedReadyReceiptsLocked() {
        pruneHostedReadyReceiptsLocked()
        readHostedReadyReceiptsLocked().keys.forEach { reportId ->
            runCatching { deleteDirectory(root.resolve(reportId)) }
        }
    }

    private fun pruneHostedReadyReceiptsLocked() {
        val receipts = readHostedReadyReceiptsLocked()
        if (receipts.isEmpty()) return
        val intents = runCatching { readHostedDeletionIntentsLocked().keys }.getOrDefault(emptySet())
        val cutoff = nowMs() - HOSTED_READY_RECEIPT_RETENTION_MS
        val retained = receipts.filter { (id, receipt) -> id in intents || receipt.readyAtEpochMs >= cutoff }
        if (retained != receipts) writeHostedReadyReceiptsLocked(retained)
    }

    private fun hostedReadyReceiptIdsLocked(): Set<String> =
        runCatching { readHostedReadyReceiptsLocked().keys }.getOrDefault(emptySet())

    private fun readHostedReadyReceiptsLocked(): Map<String, HostedReadyReceipt> {
        if (!hostedReadyReceiptsFile.isFile) return emptyMap()
        require(hostedReadyReceiptsFile.length() <= MAX_READY_RECEIPTS_BYTES) {
            "hosted READY receipt state exceeds its size limit"
        }
        return JSON.decodeFromString<Map<String, HostedReadyReceipt>>(hostedReadyReceiptsFile.readText())
            .filter { (id, receipt) ->
                ID_PATTERN.matches(id) &&
                    receipt.destinationKind == DiagnosticsDestinationKind.HOSTED &&
                    receipt.binding.serverInstanceId == HOSTED_DIAGNOSTICS_COLLECTOR_ID &&
                    receipt.readyAtEpochMs >= 0
            }
    }

    private fun writeHostedReadyReceiptsLocked(receipts: Map<String, HostedReadyReceipt>) {
        val parent = checkNotNull(hostedReadyReceiptsFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "unable to create diagnostics state directory" }
        if (receipts.isEmpty()) {
            if (hostedReadyReceiptsFile.exists()) {
                check(hostedReadyReceiptsFile.delete()) { "unable to clear hosted READY receipts" }
                syncDirectory(parent)
            }
            return
        }
        require(receipts.keys.all(ID_PATTERN::matches)) { "invalid hosted READY receipt" }
        val bytes = JSON.encodeToString<Map<String, HostedReadyReceipt>>(receipts.toSortedMap()).encodeToByteArray()
        require(bytes.size <= MAX_READY_RECEIPTS_BYTES) { "too many hosted READY receipts" }
        writeAtomic(hostedReadyReceiptsFile, bytes)
    }

    private fun readHostedDeletionIntentsLocked(): Map<String, Long> {
        if (!hostedDeletionIntentsFile.isFile) return emptyMap()
        require(hostedDeletionIntentsFile.length() <= MAX_DELETION_INTENTS_BYTES) {
            "hosted deletion intent state exceeds its size limit"
        }
        return JSON.decodeFromString<Map<String, Long>>(hostedDeletionIntentsFile.readText())
            .filterKeys(ID_PATTERN::matches)
    }

    private fun writeHostedDeletionIntentsLocked(intents: Map<String, Long>) {
        val parent = checkNotNull(hostedDeletionIntentsFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "unable to create diagnostics state directory" }
        if (intents.isEmpty()) {
            if (hostedDeletionIntentsFile.exists()) {
                check(hostedDeletionIntentsFile.delete()) { "unable to clear hosted deletion intents" }
                syncDirectory(parent)
            }
            return
        }
        require(intents.keys.all(ID_PATTERN::matches)) { "invalid hosted deletion intent" }
        val bytes = JSON.encodeToString<Map<String, Long>>(intents.toSortedMap()).encodeToByteArray()
        require(bytes.size <= MAX_DELETION_INTENTS_BYTES) { "too many hosted deletion intents" }
        writeAtomic(hostedDeletionIntentsFile, bytes)
    }

    @Serializable
    private data class HostedReadyReceipt(
        val binding: DiagnosticsBinding,
        @SerialName("destination_kind") val destinationKind: DiagnosticsDestinationKind,
        @SerialName("ready_at_epoch_ms") val readyAtEpochMs: Long,
    )

    @Serializable
    private data class PendingIndex(
        val fingerprints: Map<String, Long> = emptyMap(),
        val throttles: Map<String, Long> = emptyMap(),
        @SerialName("retry_after") val retryAfter: Map<String, Long> = emptyMap(),
    ) {
        fun pruned(now: Long, retention: Long): PendingIndex {
            val cutoff = now - retention
            return copy(
                fingerprints = fingerprints.filterValues { it >= cutoff },
                throttles = throttles.filterValues { it >= cutoff },
                retryAfter = retryAfter.filterValues { it > now },
            )
        }
    }

    private companion object {
        const val DEFAULT_MAX_REPORTS = 3
        const val DEFAULT_RETENTION_MS = PENDING_DIAGNOSTICS_RETENTION_DAYS * 24L * 60 * 60 * 1_000
        const val MAX_CAPTURE_BYTES = 20L * 1_024 * 1_024
        const val MAX_INDEX_BYTES = 256 * 1_024
        const val MAX_DELETION_INTENTS_BYTES = 256 * 1_024
        const val MAX_READY_RECEIPTS_BYTES = 256 * 1_024
        const val HOSTED_READY_RECEIPT_RETENTION_MS =
            (HOSTED_DIAGNOSTICS_RETENTION_DAYS + PENDING_DIAGNOSTICS_RETENTION_DAYS) * 24L * 60 * 60 * 1_000
        const val BINDING_FILE = "binding.json"
        const val MANIFEST_FILE = "manifest.json"
        const val STATE_FILE = "state.json"
        const val DEVICE_FILE = "device.json"
        const val HOSTED_MANIFEST_FILE = "manifest.json"
        const val HOSTED_BUNDLE_FILE = "bundle.tar.gz"
        const val HOSTED_ENTRIES_DIRECTORY = "entries"
        const val HOSTED_ENVELOPE_PREFIX = ".hosted-envelope-"
        const val HOSTED_ENVELOPE_STAGING_PREFIX = ".hosted-envelope-staging-"
        val ID_PATTERN = Regex("^[0-9a-f]{32}$")
        val ALLOWED_ARTIFACTS = setOf(
            "device.json",
            "logs.jsonl",
            "crash/summary.json",
            "crash/stack.txt",
            "crash/tombstone.pb",
            "crash/metrickit.json",
            "breadcrumbs.jsonl",
        )
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}

const val PENDING_DIAGNOSTICS_RETENTION_DAYS = 7

private fun DiagnosticsBinding.scopeKey(): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$serverInstanceId\u0000$accountUserId".encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun File.resolveSibling(name: String): File = checkNotNull(parentFile).resolve(name)

private fun File.isWithinDirectory(directory: File): Boolean {
    val rootPath = directory.canonicalFile.path
    val candidatePath = canonicalFile.path
    return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
}
