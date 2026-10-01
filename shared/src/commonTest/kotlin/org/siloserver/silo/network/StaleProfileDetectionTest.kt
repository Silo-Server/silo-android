package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The v2 error decoder reports a 403 `profile_verification_required` together
 * with the profile identity the refused request presented, so the recovery can
 * tell a stale token on the active profile from everything else.
 */
class StaleProfileDetectionTest {

    private fun problem(code: String) =
        """{"type":"https://siloserver.org/problems/$code","title":"Forbidden","status":403,"detail":"x"}"""

    private suspend fun tokens() = TokenManagerImpl().apply {
        setServerUrl("https://silo.example")
        saveTokens("access", "refresh", 3_600)
        setProfileIdentity("kid", "pin-token")
    }

    private fun client(
        tokens: TokenManager,
        signals: AccessChangeSignals?,
        code: String = PROFILE_VERIFICATION_REQUIRED,
    ) = HttpClient(
        MockEngine {
            respond(
                content = problem(code),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        },
    ) {
        install(SiloAuthPlugin) {
            tokenManager = tokens
            accessChangeSignals = signals
        }
    }

    @Test
    fun `a stale profile refusal is reported with the presented identity`() = runTest {
        val signals = AccessChangeSignals()
        val reports = mutableListOf<StaleProfileReport>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.staleProfileReports.collect { reports += it }
        }
        val client = client(tokens(), signals)

        val result = safeApiV2Call<Unit>(ApiV2Gate.Unrestricted) { client.get("/api/v2/home/sections") }
        testScheduler.runCurrent()

        val error = assertIs<ApiResult.Error>(result)
        assertEquals(403, error.code)
        assertEquals(PROFILE_VERIFICATION_REQUIRED, error.error, "callers still see the error")
        val report = reports.single()
        assertEquals("kid", report.profileId)
        assertEquals("pin-token", report.profileToken)
        assertTrue(report.requestUrl.startsWith("https://silo.example/"))
    }

    @Test
    fun `other refusals and household management are not reported`() = runTest {
        val signals = AccessChangeSignals()
        val reports = mutableListOf<StaleProfileReport>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.staleProfileReports.collect { reports += it }
        }
        val tokens = tokens()

        val denied = client(tokens, signals, code = "permission_denied")
        safeApiV2Call<Unit>(ApiV2Gate.Unrestricted) { denied.get("/api/v2/home/sections") }
        // A non-primary profile editing the household gets the same problem
        // type; that is a missing permission, not a stale token.
        val stale = client(tokens, signals)
        safeApiV2Call<Unit>(ApiV2Gate.Unrestricted) { stale.patch("/api/v2/profiles/parent") }
        testScheduler.runCurrent()

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a request without a profile is not reported`() = runTest {
        val signals = AccessChangeSignals()
        val reports = mutableListOf<StaleProfileReport>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.staleProfileReports.collect { reports += it }
        }
        val tokens = tokens().apply { setProfileIdentity(null, null) }

        safeApiV2Call<Unit>(ApiV2Gate.Unrestricted) { client(tokens, signals).get("/api/v2/home/sections") }
        testScheduler.runCurrent()

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a client without signals keeps the plain error`() = runTest {
        val result = safeApiV2Call<Unit>(ApiV2Gate.Unrestricted) {
            client(tokens(), signals = null).get("/api/v2/home/sections")
        }

        assertEquals(PROFILE_VERIFICATION_REQUIRED, assertIs<ApiResult.Error>(result).error)
    }

    @Test
    fun `household management is told apart from profile reads`() {
        assertTrue(isHouseholdManagementRequest(HttpMethod.Patch, "/api/v2/profiles/p1"))
        assertTrue(isHouseholdManagementRequest(HttpMethod.Post, "/api/v2/profiles"))
        assertTrue(isHouseholdManagementRequest(HttpMethod.Delete, "/api/v2/profiles/p1/avatar"))
        assertTrue(isHouseholdManagementRequest(HttpMethod.Get, "/api/v2/profiles/household/sessions"))
        // The picker's list and PIN entry fail on a stale token and must recover.
        assertFalse(isHouseholdManagementRequest(HttpMethod.Get, "/api/v2/profiles"))
        assertFalse(isHouseholdManagementRequest(HttpMethod.Post, "/api/v2/profiles/p1/verify-pin"))
        assertFalse(isHouseholdManagementRequest(HttpMethod.Get, "/api/v2/home/sections"))
    }
}
