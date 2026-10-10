package org.siloserver.silo.network

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** v2 problem code for a profile header the server no longer accepts. */
const val PROFILE_VERIFICATION_REQUIRED = "profile_verification_required"

/**
 * Whether a failed read says the viewer may no longer see the resource: 404,
 * 410, or a 403 other than [PROFILE_VERIFICATION_REQUIRED] (a stale profile
 * proof, which profile recovery handles). Transport failures, 401, and 5xx are
 * transient and say nothing about access.
 */
fun ApiResult<*>.isAccessRefusal(): Boolean =
    this is ApiResult.Error &&
        (code == 404 || code == 410 || (code == 403 && error != PROFILE_VERIFICATION_REQUIRED))

/**
 * How the client learns that the server changed what the signed-in viewer may
 * access, without the session ending.
 *
 * An administrator can move an account to another access group, or change its
 * permissions or playback-quality override, while the user is signed in. The
 * server then raises its access-policy revision: PIN profile tokens minted
 * before the change stop working (403 `profile_verification_required`), and the
 * events socket sends `{"type":"access_changed"}` and closes with code 4001.
 *
 * This type only carries those observations. [reportStaleProfile] feeds the
 * profile recovery in the repository layer; [changes] tells screens to refetch
 * access-dependent data. Both are inert when the server never produces them.
 */
class AccessChangeSignals(
    /** Milliseconds on a monotonic clock; tests substitute virtual time. */
    private val nowMillis: () -> Long = monotonicMillis(),
) {
    private val _staleProfileReports = MutableSharedFlow<StaleProfileReport>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every request the server refused because its profile proof is stale. */
    val staleProfileReports: SharedFlow<StaleProfileReport> = _staleProfileReports.asSharedFlow()

    /** Recent reports, newest last; [revision] moves only after a report is logged here. */
    private val reports = MutableStateFlow(emptyList<AccessChangeReport>())
    private val _revision = MutableStateFlow(0L)

    /** Raised once per events-socket connection that reports an access change. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Access changes reported after collection starts, coalesced. The frame and
     * close of one connection count once. The Home and notifications sockets
     * each detect the same change on their own server-side check, which runs
     * every 15 seconds from each connection's start, so their reports can be
     * that far apart: the first report refreshes at once, and reports within
     * [ACCESS_CHANGE_COALESCE_MS] of it are taken as the same change. The
     * exception is a report from a connection opened after that refresh. Each
     * connection reports once and then reconnects, and a new connection
     * compares against the policy its own ticket was minted under, so its
     * report is a change the refresh did not load, and it refreshes again.
     */
    val changes: Flow<Unit> = flow { emitAll(changes(AccessChangeCursor())) }

    /**
     * [changes] for a holder that outlives its screen, such as a ViewModel
     * whose screen leaves composition while another destination is in front.
     * The [cursor] remembers which revision the holder has applied, so when
     * collection resumes, a change reported while nothing was collecting
     * refreshes at once instead of being skipped. Coalescing follows the
     * cursor as well, so the second socket's report of a change already
     * applied does not refresh again on return.
     */
    fun changes(cursor: AccessChangeCursor): Flow<Unit> = flow {
        revision.collect { current ->
            val applied = cursor.appliedRevision
            if (applied != null && current <= applied) return@collect
            cursor.appliedRevision = current
            // The first collection starts from whatever the holder loaded.
            if (applied == null) return@collect
            val now = nowMillis()
            val last = cursor.lastRefreshAt
            val pending = reports.value.filter { it.revision in (applied + 1)..current }
            // Reports older than the log reach are unknown; refresh for them.
            val complete = pending.size.toLong() == current - applied
            val covered = last != null && complete && now - last < ACCESS_CHANGE_COALESCE_MS &&
                pending.all { report -> report.connectedAt == null || report.connectedAt < last }
            if (!covered) {
                cursor.lastRefreshAt = now
                emit(Unit)
            }
        }
    }

    fun reportStaleProfile(report: StaleProfileReport) {
        _staleProfileReports.tryEmit(report)
    }

    /** The time to pass to [reportAccessChanged] for a connection whose ticket was just minted. */
    fun connectionTime(): Long = nowMillis()

    /**
     * Records one access-change report. [connectedAt] is the [connectionTime]
     * taken after the reporting socket's ticket was minted; null for a report
     * that does not come from an events socket, which coalesces by time only.
     */
    fun reportAccessChanged(connectedAt: Long? = null) {
        var logged = 0L
        reports.update { log ->
            logged = (log.lastOrNull()?.revision ?: 0L) + 1
            (log + AccessChangeReport(logged, connectedAt)).takeLast(REPORT_LOG_SIZE)
        }
        _revision.update { maxOf(it, logged) }
    }

    companion object {
        /** Longer than the server's 15-second socket access check. */
        const val ACCESS_CHANGE_COALESCE_MS = 20_000L

        private const val REPORT_LOG_SIZE = 32

        /** Close code the events socket uses after an `access_changed` frame. */
        const val ACCESS_CHANGED_CLOSE_CODE: Short = 4001

        /** Frame `type` of the access-change notice on the events socket. */
        const val ACCESS_CHANGED_FRAME_TYPE = "access_changed"
    }
}

/**
 * The access changes one holder has already applied; see
 * [AccessChangeSignals.changes]. Keep one per ViewModel and collect it from one
 * place at a time (the screen's main-thread effect).
 */
class AccessChangeCursor {
    internal var appliedRevision: Long? = null
    internal var lastRefreshAt: Long? = null
}

private class AccessChangeReport(val revision: Long, val connectedAt: Long?)

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/**
 * One request refused with 403 `profile_verification_required`, described by
 * the identity it actually presented. The recovery acts only when that identity
 * is still the active one, which is what keeps a stale background request (or a
 * second report for an identity already cleared) from doing anything.
 */
data class StaleProfileReport(
    val requestUrl: String,
    val profileId: String,
    val profileToken: String?,
    /** The scope a pinned (background/outbox) request was bound to, if any. */
    val pinnedScope: AuthScopeSnapshot?,
) {
    override fun toString(): String =
        "StaleProfileReport(requestUrl=<redacted>, profileId=<redacted>, " +
            "profileToken=<redacted>, pinned=${pinnedScope != null})"
}

/** Client attribute through which the v2 error decoder finds the signals. */
internal val AccessChangeSignalsKey: AttributeKey<AccessChangeSignals> =
    AttributeKey("SiloAccessChangeSignals")

/**
 * Report this response if it is a stale-profile refusal for a request that ran
 * on a profile identity. Called by the v2 error decoder, so it sees every v2
 * call without the auth plugin having to read response bodies.
 */
internal fun HttpResponse.reportStaleProfileIfRefused(problemCode: String) {
    if (status != HttpStatusCode.Forbidden || problemCode != PROFILE_VERIFICATION_REQUIRED) return
    val signals = call.client.attributes.getOrNull(AccessChangeSignalsKey) ?: return
    val request = call.request
    // Diagnostics uploads choose their profile headers themselves.
    if (request.attributes.contains(DiagnosticsUploadAuthorizationKey) ||
        request.attributes.contains(DiagnosticsRequestScopeKey)
    ) return
    if (isHouseholdManagementRequest(request.method, request.url.encodedPath)) return
    val profileId = request.headers["X-Profile-Id"]?.takeIf { it.isNotBlank() } ?: return
    signals.reportStaleProfile(
        StaleProfileReport(
            requestUrl = request.url.toString(),
            profileId = profileId,
            profileToken = request.headers["X-Profile-Token"]?.takeIf { it.isNotBlank() },
            pinnedScope = request.attributes.getOrNull(AuthScopeAttributeKey),
        ),
    )
}

/**
 * Household management (creating, editing, or deleting profiles, avatars, and
 * the household session list) answers `profile_verification_required` when the
 * active profile is not the verified primary profile. That is a missing
 * permission, not a stale token, so it must not clear the profile. A stale token
 * on these screens still recovers through the next ordinary read.
 */
internal fun isHouseholdManagementRequest(method: HttpMethod, path: String): Boolean {
    val profilesPath = path.startsWith("/api/v2/profiles") || path.startsWith("/api/v1/profiles")
    if (!profilesPath || path.endsWith("/verify-pin")) return false
    return method != HttpMethod.Get || path.contains("/profiles/household")
}
