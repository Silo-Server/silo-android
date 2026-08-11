package org.siloserver.silo.network

/**
 * Thrown instead of sending a request whose credentials the client cannot or
 * must not spend.
 *
 * Two reasons, both meaning "do not put this on the wire":
 * - [REQUIRED_AUTH_UNAVAILABLE] — the request demanded auth and there is no
 *   usable token for its scope.
 * - [CREDENTIALS_REPUDIATED] — a proactive refresh was rejected, so the session
 *   is already torn down. The access token may still have time left, which is
 *   exactly the danger: the server could honour a write for a session the app
 *   has ended.
 *
 * Subclasses [IllegalStateException] because that is what the required-auth
 * path has always thrown, so existing handlers keep working. Callers that
 * classify failures should treat this as **retriable after re-authentication**,
 * not permanent: `safeApiCall` already maps it to [ApiResult.NetworkError], and
 * `DownloadWorker` catches it explicitly so a repudiated session retries rather
 * than deleting a part-downloaded file.
 */
class SiloAuthUnavailableException(val reason: String) : IllegalStateException(reason) {
    companion object {
        const val REQUIRED_AUTH_UNAVAILABLE = "required_silo_auth_unavailable"
        const val CREDENTIALS_REPUDIATED = "silo_auth_credentials_repudiated"
    }
}
