package org.siloserver.silo.common.diagnostics.crash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Parsed form of a crash marker file (see [CrashCapture] for the write side).
 * Parsed only at next launch, on a normal thread, with a lenient decoder — a
 * torn or unreadable marker is discarded, never retried forever.
 */
@Serializable
data class CrashMarker(
    @SerialName("marker_version") val markerVersion: Int = 1,
    @SerialName("written_at_ms") val writtenAtMs: Long,
    @SerialName("process_name") val processName: String = "",
    val pid: Int = 0,
    @SerialName("capture_session_id") val captureSessionId: String = "",
    @SerialName("thread_name") val threadName: String = "",
    @SerialName("exception_class") val exceptionClass: String = "",
    @SerialName("exception_message") val exceptionMessage: String = "",
    @SerialName("stack_text") val stackText: String = "",
    val foreground: Boolean = false,
    val session: MarkerSession? = null,
    @SerialName("device_snapshot") val deviceSnapshot: JsonObject? = null,
    @SerialName("playback_session_ids") val playbackSessionIds: List<String> = emptyList(),
    @SerialName("dropped_lines") val droppedLines: Long = 0,
    @SerialName("log_lines") val logLines: List<JsonObject> = emptyList(),
) {
    @Serializable
    data class MarkerSession(
        @SerialName("server_instance_id") val serverInstanceId: String,
        @SerialName("account_user_id") val accountUserId: String,
        @SerialName("profile_id") val profileId: String? = null,
        @SerialName("consent_mode") val consentMode: String = "ask",
        @SerialName("notice_version") val noticeVersion: Int = 1,
        @SerialName("app_version") val appVersion: String = "",
        @SerialName("app_build") val appBuild: String = "",
        val platform: String = "android",
        @SerialName("os_version") val osVersion: String = "",
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(bytes: ByteArray): CrashMarker? =
            runCatching { json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull()
    }
}
