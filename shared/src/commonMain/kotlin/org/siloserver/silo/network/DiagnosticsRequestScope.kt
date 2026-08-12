package org.siloserver.silo.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey

/**
 * A persistent Silo credential captured before entering an identity send
 * lease. The access token is intentionally frozen: the leased request surfaces
 * a 401 for a later retry instead of refreshing and re-entering the barrier.
 */
data class DiagnosticsUploadAuthorization(
    val serverId: String,
    val serverUrl: String,
    val accessToken: String,
    val activeProfileId: String?,
    val identityGeneration: Long,
) {
    init {
        require(serverId.isNotBlank()) { "diagnostics authorization requires a server id" }
        require(serverUrl.isNotBlank()) { "diagnostics authorization requires a server URL" }
        require(accessToken.isNotBlank()) { "diagnostics authorization requires an access token" }
    }

    override fun toString(): String =
        "DiagnosticsUploadAuthorization(" +
            "serverId=<redacted>, serverUrl=<redacted>, accessToken=<redacted>, " +
            "activeProfileId=<redacted>, identityGeneration=<redacted>)"
}

enum class DiagnosticsProfileHeaderMode {
    ACTIVE,
    SUPPRESS,
    EXACT,
}

data class DiagnosticsRequestScope(
    val mode: DiagnosticsProfileHeaderMode,
    val exactProfileId: String? = null,
) {
    init {
        require(mode == DiagnosticsProfileHeaderMode.EXACT || exactProfileId == null) {
            "exactProfileId is only valid for EXACT diagnostics requests"
        }
        require(mode != DiagnosticsProfileHeaderMode.EXACT || !exactProfileId.isNullOrBlank()) {
            "EXACT diagnostics requests require a profile id"
        }
    }
}

val DiagnosticsRequestScopeKey: AttributeKey<DiagnosticsRequestScope> =
    AttributeKey("SiloDiagnosticsRequestScope")

/**
 * Exact, already-captured authorization for a diagnostics upload whose send
 * start is serialized with identity mutation. SiloAuthPlugin must not read or
 * refresh TokenManager while handling this request: Android's persistent token
 * manager uses that same identity barrier, so doing so would deadlock.
 */
internal val DiagnosticsUploadAuthorizationKey: AttributeKey<DiagnosticsUploadAuthorization> =
    AttributeKey("SiloDiagnosticsUploadAuthorization")

fun HttpRequestBuilder.diagnosticsProfileScope(capturedProfileId: String?) {
    attributes.put(
        DiagnosticsRequestScopeKey,
        capturedProfileId?.let {
            DiagnosticsRequestScope(DiagnosticsProfileHeaderMode.EXACT, exactProfileId = it)
        } ?: DiagnosticsRequestScope(DiagnosticsProfileHeaderMode.SUPPRESS),
    )
}

internal fun HttpRequestBuilder.diagnosticsUploadAuthorization(
    authorization: DiagnosticsUploadAuthorization,
) {
    attributes.put(DiagnosticsUploadAuthorizationKey, authorization)
}
