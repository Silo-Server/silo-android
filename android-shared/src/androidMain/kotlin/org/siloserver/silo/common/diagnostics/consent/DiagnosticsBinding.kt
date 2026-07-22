package org.siloserver.silo.common.diagnostics.consent

import kotlinx.serialization.Serializable
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode

/**
 * The consent scope: one server instance + one account. Every consent record,
 * pending report, sent-history entry, and recent-session entry is keyed by
 * this pair — never by device or by the local ServerRegistry id alone.
 * `profile_id` is attribution only, never a consent scope.
 */
@Serializable
data class DiagnosticsBinding(
    val serverInstanceId: String,
    val accountUserId: String,
) {
    val storageKey: String get() = "$serverInstanceId|$accountUserId"
}

/** The user-facing crash-reports setting for one binding. */
@Serializable
enum class ConsentChoice { ASK, ALWAYS, NEVER }

/**
 * Wire mode for a report's manifest. Ask *and* Never render as `prompt` —
 * a Never binding never uploads at all, and any leftover report from before
 * the switch still claims prompt-consent semantics. Manual captures always
 * carry `manual` regardless of the binding's crash-reports setting.
 */
fun ConsentChoice.toManifestMode(): DiagnosticsConsentMode = when (this) {
    ConsentChoice.ALWAYS -> DiagnosticsConsentMode.ALWAYS
    ConsentChoice.ASK, ConsentChoice.NEVER -> DiagnosticsConsentMode.PROMPT
}

@Serializable
data class ConsentRecord(
    val mode: ConsentChoice,
    val noticeVersion: Int,
    val updatedAtEpochMs: Long,
)

@Serializable
data class SentReport(
    val shortId: String,
    val sentAtEpochMs: Long,
)
