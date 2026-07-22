package org.siloserver.silo.common.diagnostics.consent

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import java.io.File

/**
 * Binding-scoped consent state: crash-report choice per (server, account),
 * sent short-id history, the local-serverId → server_instance_id index (what
 * lets sign-out/server-removal — which only know the local id — purge the
 * right instance-keyed records), and the last-known status cache that lets a
 * crash be bound while offline.
 *
 * Plain JSON files under the diagnostics directory, atomic tmp+fsync+rename
 * writes, in-process `synchronized` only (single-process app).
 */
class DiagnosticsConsentStore(
    baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(baseDir, "consent")
    private val lock = Any()

    /**
     * Purge cascade for Never — set by the coordinator after construction
     * (pending reports, breadcrumbs, session tracker live elsewhere).
     */
    @Volatile
    var onNeverSelected: ((DiagnosticsBinding) -> Unit)? = null

    @Serializable
    data class CachedStatus(
        val status: DiagnosticsStatusResponse,
        val accountUserId: String,
        val cachedAtEpochMs: Long,
    )

    // ---- consent records -------------------------------------------------

    /**
     * The single read/reconcile path: returns the binding's record, demoting
     * a stale-notice ALWAYS back to ASK (and bumping the stored version).
     * Called on every status refresh AND again right before each upload, so a
     * notice bump between refresh and send can never ship stale consent.
     */
    fun record(binding: DiagnosticsBinding, currentNoticeVersion: Int): ConsentRecord = synchronized(lock) {
        val records = readRecords()
        val existing = records[binding.storageKey]
            ?: return@synchronized ConsentRecord(ConsentChoice.ASK, currentNoticeVersion, clock())
        if (existing.noticeVersion == currentNoticeVersion) return@synchronized existing
        val reconciled = ConsentRecord(
            mode = if (existing.mode == ConsentChoice.ALWAYS) ConsentChoice.ASK else existing.mode,
            noticeVersion = currentNoticeVersion,
            updatedAtEpochMs = clock(),
        )
        writeRecords(records + (binding.storageKey to reconciled))
        reconciled
    }

    fun setMode(binding: DiagnosticsBinding, mode: ConsentChoice, noticeVersion: Int) {
        synchronized(lock) {
            val updated = ConsentRecord(mode, noticeVersion, clock())
            writeRecords(readRecords() + (binding.storageKey to updated))
        }
        if (mode == ConsentChoice.NEVER) onNeverSelected?.invoke(binding)
    }

    /** Raw stored record without notice reconciliation (UI display). */
    fun storedRecord(binding: DiagnosticsBinding): ConsentRecord? = synchronized(lock) {
        readRecords()[binding.storageKey]
    }

    // ---- sent history ----------------------------------------------------

    fun recordSent(binding: DiagnosticsBinding, shortId: String) = synchronized(lock) {
        val history = readSentHistory().toMutableMap()
        val entries = history[binding.storageKey].orEmpty()
            .filterNot { it.shortId.equals(shortId, ignoreCase = true) }
        history[binding.storageKey] = (entries + SentReport(shortId, clock())).takeLast(MAX_SENT_HISTORY)
        writeSentHistory(history)
    }

    fun sentHistory(binding: DiagnosticsBinding): List<SentReport> = synchronized(lock) {
        readSentHistory()[binding.storageKey].orEmpty().sortedByDescending { it.sentAtEpochMs }
    }

    // ---- server-instance index + last-known status -----------------------

    fun rememberServerInstance(localServerId: String, serverInstanceId: String) = synchronized(lock) {
        writeServerIndex(readServerIndex() + (localServerId to serverInstanceId))
    }

    fun serverInstanceForLocalId(localServerId: String): String? = synchronized(lock) {
        readServerIndex()[localServerId]
    }

    /** Removes the index entry, returning the instance id so callers can purge by it. */
    fun forgetLocalServer(localServerId: String): String? = synchronized(lock) {
        val index = readServerIndex()
        val instanceId = index[localServerId] ?: return@synchronized null
        writeServerIndex(index - localServerId)
        writeStatusCache(readStatusCache() - localServerId)
        instanceId
    }

    fun cacheStatus(localServerId: String, status: DiagnosticsStatusResponse, accountUserId: String) = synchronized(lock) {
        writeStatusCache(
            readStatusCache() + (localServerId to CachedStatus(status, accountUserId, clock())),
        )
    }

    fun cachedStatus(localServerId: String): CachedStatus? = synchronized(lock) {
        readStatusCache()[localServerId]
    }

    // ---- purge -----------------------------------------------------------

    fun purge(binding: DiagnosticsBinding) = synchronized(lock) {
        writeRecords(readRecords() - binding.storageKey)
        writeSentHistory(readSentHistory() - binding.storageKey)
    }

    fun purgeServer(serverInstanceId: String) = synchronized(lock) {
        val prefix = "$serverInstanceId|"
        writeRecords(readRecords().filterKeys { !it.startsWith(prefix) })
        writeSentHistory(readSentHistory().filterKeys { !it.startsWith(prefix) })
    }

    // ---- files -----------------------------------------------------------

    private fun readRecords(): Map<String, ConsentRecord> =
        readMap(File(dir, "consent-records.json"), ConsentRecord.serializer())

    private fun writeRecords(value: Map<String, ConsentRecord>) =
        writeMap(File(dir, "consent-records.json"), ConsentRecord.serializer(), value)

    private fun readSentHistory(): Map<String, List<SentReport>> =
        readMap(
            File(dir, "sent-history.json"),
            kotlinx.serialization.builtins.ListSerializer(SentReport.serializer()),
        )

    private fun writeSentHistory(value: Map<String, List<SentReport>>) =
        writeMap(
            File(dir, "sent-history.json"),
            kotlinx.serialization.builtins.ListSerializer(SentReport.serializer()),
            value,
        )

    private fun readServerIndex(): Map<String, String> =
        readMap(File(dir, "server-index.json"), String.serializer())

    private fun writeServerIndex(value: Map<String, String>) =
        writeMap(File(dir, "server-index.json"), String.serializer(), value)

    private fun readStatusCache(): Map<String, CachedStatus> =
        readMap(File(dir, "last-status.json"), CachedStatus.serializer())

    private fun writeStatusCache(value: Map<String, CachedStatus>) =
        writeMap(File(dir, "last-status.json"), CachedStatus.serializer(), value)

    private fun <V> readMap(file: File, valueSerializer: kotlinx.serialization.KSerializer<V>): Map<String, V> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), valueSerializer), file.readText())
        }.onFailure { Log.w(TAG, "read failed for ${file.name}", it) }.getOrDefault(emptyMap())
    }

    private fun <V> writeMap(
        file: File,
        valueSerializer: kotlinx.serialization.KSerializer<V>,
        value: Map<String, V>,
    ) {
        writeAtomic(file, json.encodeToString(MapSerializer(String.serializer(), valueSerializer), value))
    }

    private fun writeAtomic(target: File, text: String) =
        org.siloserver.silo.common.diagnostics.writeDiagnosticsFileAtomic(target, text, TAG)

    private companion object {
        const val TAG = "DiagConsentStore"
        const val MAX_SENT_HISTORY = 10
        val json = Json { ignoreUnknownKeys = true }
    }
}
