package org.siloserver.silo.common.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pre-rendered, `@Volatile`-published context the crash handler reads on the
 * dying thread. Everything here is produced on normal threads (status refresh,
 * profile switch, snapshot warm-up) so the UEH path does volatile reads and
 * byte splices only — no JSON framework, no probes, no locks.
 */
object CrashContextCache {

    /** Null until a binding has been established (first status refresh / cached status). */
    @Volatile
    var session: CrashSessionContext? = null

    /** Rendered [org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSnapshot] JSON (pre_failure). */
    @Volatile
    var deviceSnapshotJson: ByteArray? = null

    @Volatile
    var recentPlaybackSessionIds: List<String> = emptyList()

    /** Maintained by an ActivityLifecycleCallbacks observer in each app. */
    val foreground = AtomicBoolean(false)

    fun clearSession() {
        session = null
        recentPlaybackSessionIds = emptyList()
    }
}

/**
 * Immutable session/binding context, rendered to JSON once at construction
 * (on a normal thread) so the crash handler can splice it verbatim.
 */
class CrashSessionContext(
    val serverInstanceId: String,
    val accountUserId: String,
    val profileId: String?,
    /** Local consent choice wire value: ask | always | never. */
    val consentMode: String,
    val noticeVersion: Int,
    val appVersion: String,
    val appBuild: String,
    /** android | android-tv */
    val platform: String,
    val osVersion: String,
) {
    val prerenderedJson: String = buildString(256) {
        append('{')
        appendJsonField("server_instance_id", serverInstanceId); append(',')
        appendJsonField("account_user_id", accountUserId); append(',')
        if (profileId != null) {
            appendJsonField("profile_id", profileId); append(',')
        }
        appendJsonField("consent_mode", consentMode); append(',')
        append("\"notice_version\":").append(noticeVersion).append(',')
        appendJsonField("app_version", appVersion); append(',')
        appendJsonField("app_build", appBuild); append(',')
        appendJsonField("platform", platform); append(',')
        appendJsonField("os_version", osVersion)
        append('}')
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":")
    appendJsonEscaped(value)
}

/** Minimal JSON string escaping (quote, backslash, control chars) — no regex, no library. */
internal fun StringBuilder.appendJsonEscaped(value: String) {
    append('"')
    for (ch in value) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> {
                append("\\u")
                val hex = ch.code.toString(16)
                repeat(4 - hex.length) { append('0') }
                append(hex)
            }
            else -> append(ch)
        }
    }
    append('"')
}
