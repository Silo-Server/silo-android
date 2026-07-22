package org.siloserver.silo.common.diagnostics

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.decodeDiagnosticsManifest

@Serializable
data class PendingReportBinding(
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("account_user_id") val accountUserId: String,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("ownership_generation") val ownershipGeneration: Long,
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

interface PendingReportStore {
    fun save(capture: PendingReportCapture): PendingReport
    fun list(binding: DiagnosticsBinding): List<PendingReport>
    fun load(id: String): PendingReport?
    fun delete(id: String)
    fun purge(binding: DiagnosticsBinding)
    fun markState(id: String, status: PendingReportStatus, errorCode: String? = null)
    fun hasSeenFingerprint(fingerprint: String): Boolean
    fun markThrottled(key: String, atEpochMs: Long)
    fun isThrottled(key: String, windowMs: Long): Boolean
    fun retryAfterDeadline(binding: DiagnosticsBinding): Long?
    fun setRetryAfterDeadline(binding: DiagnosticsBinding, deadlineEpochMs: Long)
    fun clearRetryAfterDeadline(binding: DiagnosticsBinding)
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
    private val lock = Any()

    init {
        require(maxReportsPerBinding > 0)
        require(retentionMs > 0)
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

    override fun purge(binding: DiagnosticsBinding) = synchronized(lock) {
        val removed = reportsLocked().filter { it.binding.binding == binding }
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
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_CAPTURE_BYTES = 20L * 1_024 * 1_024
        const val MAX_INDEX_BYTES = 256 * 1_024
        const val BINDING_FILE = "binding.json"
        const val MANIFEST_FILE = "manifest.json"
        const val STATE_FILE = "state.json"
        const val DEVICE_FILE = "device.json"
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

private fun DiagnosticsBinding.scopeKey(): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$serverInstanceId\u0000$accountUserId".encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun File.resolveSibling(name: String): File = checkNotNull(parentFile).resolve(name)
