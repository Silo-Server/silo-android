package org.siloserver.silo.common.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface ProcessStateSummaryPublisher {
    fun publish(summary: ByteArray)
}

@Serializable
data class DiagnosticsRunRecord(
    val token: String,
    val binding: DiagnosticsBinding,
    val profileId: String? = null,
    val profileEligible: Boolean,
    val processStartedAtEpochMs: Long,
    val captureSessionId: String,
    val ownershipGeneration: Long,
    val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
)

/** Bounded local mapping from an opaque process-state token to validated capture identity. */
class DiagnosticsRunLedger(
    noBackupFilesDir: File,
    private val processStateSummaryPublisher: ProcessStateSummaryPublisher = ProcessStateSummaryPublisher { },
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val deleteRecursively: (File) -> Boolean = File::deleteRecursively,
    private val directorySync: (File) -> Unit = ::syncDiagnosticsDirectory,
    private val atomicRename: (File, File) -> Unit = ::renameDiagnosticsFileAtomically,
) {
    private val directory = noBackupFilesDir.resolve("client-diagnostics")
    private val file = directory.resolve("run-ledger.json")
    private val mutex = Mutex()

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    suspend fun beginRun(
        context: DiagnosticsCaptureContext,
        processStartedAtEpochMs: Long,
        captureSessionId: String,
    ): String {
        require(context.profileEligible) { "ineligible profiles cannot own diagnostics runs" }
        require(processStartedAtEpochMs >= 0) { "processStartedAtEpochMs must be non-negative" }
        require(captureSessionId.isNotBlank()) { "captureSessionId must not be blank" }
        val token = tokenFactory().lowercase()
        require(TOKEN_PATTERN.matches(token)) { "run token must be 128-bit lowercase hex" }
        val record = DiagnosticsRunRecord(
            token = token,
            binding = context.binding,
            profileId = context.profileId,
            profileEligible = context.profileEligible,
            processStartedAtEpochMs = processStartedAtEpochMs,
            captureSessionId = captureSessionId,
            ownershipGeneration = context.ownershipGeneration,
            destinationKind = context.destinationKind,
        )
        mutex.withLock {
            val records = (listOf(record) + load())
                .distinctBy(DiagnosticsRunRecord::token)
                .sortedByDescending(DiagnosticsRunRecord::processStartedAtEpochMs)
                .take(maxRecords)
            persist(records)
        }
        // The OS-visible summary deliberately contains no identity or capture metadata.
        processStateSummaryPublisher.publish(token.encodeToByteArray())
        return token
    }

    suspend fun find(token: String): DiagnosticsRunRecord? {
        if (!TOKEN_PATTERN.matches(token)) return null
        return mutex.withLock { load().firstOrNull { it.token == token } }
    }

    suspend fun purge(binding: DiagnosticsBinding) {
        mutex.withLock {
            persist(load().filterNot { it.binding == binding })
        }
    }

    suspend fun clear() {
        mutex.withLock {
            listOf(file, temporaryFile()).forEach { evidence ->
                deleteDiagnosticsEvidenceStrictly(evidence, deleteRecursively, directorySync)
            }
        }
    }

    private fun load(): List<DiagnosticsRunRecord> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_LEDGER_BYTES) return emptyList()
        return runCatching { JSON.decodeFromString<List<DiagnosticsRunRecord>>(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            .orEmpty()
            .filter { record ->
                TOKEN_PATTERN.matches(record.token) &&
                    record.captureSessionId.isNotBlank() &&
                    record.profileEligible
            }
            .take(maxRecords)
    }

    private fun persist(records: List<DiagnosticsRunRecord>) {
        check(directory.mkdirs() || directory.isDirectory) { "unable to create diagnostics ledger directory" }
        val encoded = JSON.encodeToString(records.take(maxRecords)).encodeToByteArray()
        check(encoded.size <= MAX_LEDGER_BYTES) { "diagnostics run ledger exceeds byte limit" }
        val temporary = temporaryFile()
        FileOutputStream(temporary, false).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        atomicRename(temporary, file)
        directorySync(directory)
        check(file.isFile && !temporary.exists()) { "diagnostics run ledger publish was not durable" }
    }

    private fun temporaryFile(): File = directory.resolve("run-ledger.json.tmp")

    private companion object {
        const val DEFAULT_MAX_RECORDS = 64
        const val MAX_LEDGER_BYTES = 256 * 1_024
        val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
