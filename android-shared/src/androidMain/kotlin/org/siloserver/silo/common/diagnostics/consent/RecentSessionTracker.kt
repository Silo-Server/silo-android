package org.siloserver.silo.common.diagnostics.consent

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.common.diagnostics.CrashContextCache
import java.io.File

/**
 * Bounded, persisted recent playback-session tracker (last 10 per binding).
 *
 * Both clients previously retained only the *active* session id; next-launch
 * crash reports need the recent history so admins can pivot into the server's
 * `operational_logs.playback_session_id` filter. Keyed by binding so a session
 * started against server A never appears in a report to server B.
 */
class RecentSessionTracker(
    baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(File(baseDir, "consent"), "recent-sessions.json")
    private val lock = Any()
    private val writeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Resolved lazily so PlaybackSessionManager needs no binding knowledge. */
    @Volatile
    var currentBindingProvider: () -> DiagnosticsBinding? = { null }

    @Serializable
    data class SessionEntry(val sessionId: String, val recordedAtEpochMs: Long)

    /** Non-blocking for playback callers: the file write happens on an IO worker. */
    fun recordSession(sessionId: String) {
        val binding = currentBindingProvider() ?: return
        writeScope.launch {
            synchronized(lock) {
                val all = readAll().toMutableMap()
                val entries = all[binding.storageKey].orEmpty()
                    .filterNot { it.sessionId == sessionId }
                all[binding.storageKey] = (entries + SessionEntry(sessionId, clock())).takeLast(MAX_SESSIONS)
                writeAll(all)
                CrashContextCache.recentPlaybackSessionIds = all[binding.storageKey].orEmpty().map { it.sessionId }
            }
        }
    }

    fun recent(binding: DiagnosticsBinding): List<String> = synchronized(lock) {
        readAll()[binding.storageKey].orEmpty()
            .sortedBy { it.recordedAtEpochMs }
            .map { it.sessionId }
    }

    /** Refreshes the crash-context cache after a binding change. */
    fun publishToCrashContext(binding: DiagnosticsBinding?) {
        CrashContextCache.recentPlaybackSessionIds =
            binding?.let { recent(it) } ?: emptyList()
    }

    fun purge(binding: DiagnosticsBinding) = synchronized(lock) {
        writeAll(readAll() - binding.storageKey)
    }

    fun purgeServer(serverInstanceId: String) = synchronized(lock) {
        writeAll(readAll().filterKeys { !it.startsWith("$serverInstanceId|") })
    }

    private fun readAll(): Map<String, List<SessionEntry>> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(SessionEntry.serializer())),
                file.readText(),
            )
        }.onFailure { Log.w(TAG, "read failed", it) }.getOrDefault(emptyMap())
    }

    private fun writeAll(value: Map<String, List<SessionEntry>>) {
        runCatching {
            org.siloserver.silo.common.diagnostics.writeDiagnosticsFileAtomic(
                file,
                json.encodeToString(
                    MapSerializer(String.serializer(), ListSerializer(SessionEntry.serializer())),
                    value,
                ),
                TAG,
            )
        }.onFailure { Log.w(TAG, "write failed", it) }
    }

    private companion object {
        const val TAG = "RecentSessionTracker"
        const val MAX_SESSIONS = 10
        val json = Json { ignoreUnknownKeys = true }
    }
}
