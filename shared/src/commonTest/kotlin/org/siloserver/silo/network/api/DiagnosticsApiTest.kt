package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.DiagnosticsUploadAuthorization
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsApiTest {
    @Test
    fun getStatusUsesCanonicalEndpointAndDecodesLimits() = runTest {
        val fixture = fixture(
            responseBody = """{
                "status":"available",
                "server_instance_id":"server-1",
                "accepted_schema_versions":[1],
                "max_bundle_bytes":10485760,
                "max_manifest_bytes":65536,
                "retention_days":30,
                "consent_notice_version":2
            }""".trimIndent(),
        )

        val result = assertIs<ApiResult.Success<*>>(fixture.api.getStatus())
        val status = assertIs<org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse>(result.data)

        assertEquals(HttpMethod.Get, fixture.request?.method)
        assertEquals("/api/v1/diagnostics/status", fixture.request?.url?.encodedPath)
        assertEquals(DiagnosticsAvailabilityStatus.AVAILABLE, status.status)
        assertEquals(10_485_760L, status.maxBundleBytes)
        assertEquals(2, status.consentNoticeVersion)
    }

    @Test
    fun uploadSendsExactlyTwoOrderedParts() = runTest {
        val fixture = fixture(
            responseStatus = HttpStatusCode.Created,
            responseBody = """{"report_id":"report-1","short_id":"ABC123"}""",
        )

        val result = fixture.api.upload(
            manifestJson = "manifest-payload".encodeToByteArray(),
            bundleBytes = "bundle-payload".encodeToByteArray(),
            capturedProfileId = "captured-profile",
        )

        val upload = assertIs<DiagnosticsUploadResult.Success>(result)
        assertEquals("ABC123", upload.response.shortId)
        assertEquals(HttpMethod.Post, fixture.request?.method)
        assertEquals("/api/v1/diagnostics/reports", fixture.request?.url?.encodedPath)
        assertTrue(fixture.request?.body?.contentType?.match(ContentType.MultiPart.FormData) == true)

        val body = fixture.requestBody
        val manifestIndex = body.indexOf("name=manifest")
        val bundleIndex = body.indexOf("name=bundle")
        assertTrue(manifestIndex >= 0, body)
        assertTrue(bundleIndex > manifestIndex, body)
        assertEquals(1, Regex("Content-Disposition: form-data; name=manifest").findAll(body).count())
        assertEquals(1, Regex("Content-Disposition: form-data; name=bundle").findAll(body).count())
        assertTrue(body.contains("filename=manifest.json"), body)
        assertTrue(body.contains("Content-Type: application/json"), body)
        assertTrue(body.contains("filename=bundle.tar.gz"), body)
        assertTrue(body.contains("Content-Type: application/gzip"), body)
        assertTrue(body.contains("manifest-payload"), body)
        assertTrue(body.contains("bundle-payload"), body)
    }

    @Test
    fun uploadUsesCapturedProfileAndSuppressesActiveProfileToken() = runTest {
        val fixture = fixture(responseStatus = HttpStatusCode.Created)

        fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = "captured-profile")

        assertEquals("captured-profile", fixture.request?.headers?.get("X-Profile-Id"))
        assertNull(fixture.request?.headers?.get("X-Profile-Token"))
        assertEquals("Bearer access-token", fixture.request?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun unattributedUploadSuppressesAllActiveProfileHeaders() = runTest {
        val fixture = fixture(responseStatus = HttpStatusCode.Created)

        fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = null)

        assertNull(fixture.request?.headers?.get("X-Profile-Id"))
        assertNull(fixture.request?.headers?.get("X-Profile-Token"))
        assertEquals("Bearer access-token", fixture.request?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun serverErrorCodeIsPreservedForUploaderPolicy() = runTest {
        val fixture = fixture(
            responseStatus = HttpStatusCode.BadRequest,
            responseBody = """{"error":"unsupported_schema","message":"upgrade required"}""",
        )

        val result = assertIs<DiagnosticsUploadResult.Failure>(
            fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = null),
        )

        assertEquals(400, result.httpStatus)
        assertEquals(DiagnosticsErrorCode.UNSUPPORTED_SCHEMA, result.code)
        assertEquals("upgrade required", result.message)
    }

    @Test
    fun everyStableServerErrorCodeIsPreserved() = runTest {
        val cases = listOf(
            HttpStatusCode.Unauthorized to "unauthorized",
            HttpStatusCode.Forbidden to "api_key_not_allowed",
            HttpStatusCode.Forbidden to "forbidden",
            HttpStatusCode.Forbidden to "disabled",
            HttpStatusCode.ServiceUnavailable to "storage_unavailable",
            HttpStatusCode.ServiceUnavailable to "busy",
            HttpStatusCode.PayloadTooLarge to "too_large",
            HttpStatusCode.BadRequest to "invalid_bundle",
            HttpStatusCode.TooManyRequests to "quota_exceeded",
            HttpStatusCode.BadRequest to "unsupported_schema",
            HttpStatusCode.BadRequest to "destination_mismatch",
            HttpStatusCode.BadRequest to "stale_consent",
            HttpStatusCode.BadRequest to "archive_mismatch",
            HttpStatusCode.BadRequest to "profile_mismatch",
            HttpStatusCode.Forbidden to "child_profile_forbidden",
            HttpStatusCode.InternalServerError to "internal_error",
        )

        cases.forEach { (status, errorCode) ->
            val fixture = fixture(
                responseStatus = status,
                responseBody = """{"error":"$errorCode","message":"message"}""",
            )
            val result = assertIs<DiagnosticsUploadResult.Failure>(
                fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = null),
                errorCode,
            )
            assertEquals(status.value, result.httpStatus, errorCode)
            assertEquals(DiagnosticsErrorCode.fromWire(errorCode), result.code, errorCode)
        }
    }

    @Test
    fun uploadPreservesRetryAfterForBindingScopedBackoff() = runTest {
        val fixture = fixture(
            responseStatus = HttpStatusCode.TooManyRequests,
            responseBody = """{"error":"rate_limited","message":"slow down"}""",
            retryAfterSeconds = 120,
        )

        val result = assertIs<DiagnosticsUploadResult.Failure>(
            fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = null),
        )

        assertEquals(DiagnosticsErrorCode.RATE_LIMITED, result.code)
        assertEquals(120, result.retryAfterSeconds)
    }

    @Test
    fun uploadConvertsHttpDateRetryAfterToDelaySeconds() = runTest {
        val fixture = fixture(
            responseStatus = HttpStatusCode.ServiceUnavailable,
            responseBody = """{"error":"busy","message":"try later"}""",
            retryAfterHeader = GMTDate(NOW_MS + 120_000).toHttpDate(),
        )

        val result = assertIs<DiagnosticsUploadResult.Failure>(
            fixture.api.upload(byteArrayOf(1), byteArrayOf(2), capturedProfileId = null),
        )

        assertEquals(120, result.retryAfterSeconds)
    }

    @Test
    fun statusRequestKeepsNormalActiveProfileBehavior() = runTest {
        val fixture = fixture()

        fixture.api.getStatus()

        assertEquals("active-profile", fixture.request?.headers?.get("X-Profile-Id"))
        assertEquals("active-profile-token", fixture.request?.headers?.get("X-Profile-Token"))
        assertFalse(fixture.requestBody.contains("manifest"))
    }

    @Test
    fun exactUploadDoesNotProactivelyRefreshWhileIdentityLeaseIsHeld() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val tokenManager = TokenManagerImpl(transitions).apply {
            setServerUrl("https://silo.example")
            saveTokens("expired-active", "refresh-token", 0)
            setProfileIdentity("active-profile", "active-profile-token")
        }
        val requests = mutableListOf<HttpRequestData>()
        val client = exactUploadClient(tokenManager) { request ->
            requests += request
            respond(
                content = if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    """{"access_token":"fresh","refresh_token":"fresh-refresh","expires_in":3600}"""
                } else {
                    """{"report_id":"report-1","short_id":"ABC123"}"""
                },
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val authorization = exactAuthorization(transitions.generation.value, "captured-access")

        val result = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000) {
                transitions.withCurrentGeneration(transitions.generation.value) {
                    DefaultDiagnosticsApi(client).upload(
                        byteArrayOf(1),
                        byteArrayOf(2),
                        capturedProfileId = "captured-profile",
                        authorization = authorization,
                    )
                }
            }
        }

        assertIs<DiagnosticsUploadResult.Success>(result)
        assertEquals(listOf("/api/v1/diagnostics/reports"), requests.map { it.url.encodedPath })
        assertEquals("Bearer captured-access", requests.single().headers[HttpHeaders.Authorization])
        assertEquals("captured-profile", requests.single().headers["X-Profile-Id"])
        assertNull(requests.single().headers["X-Profile-Token"])
    }

    @Test
    fun exactUploadSurfacesUnauthorizedWithoutRefreshOrSessionInvalidationUnderLease() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val tokenManager = TokenManagerImpl(transitions).apply {
            setServerUrl("https://silo.example")
            saveTokens("rejected-active", "refresh-token", 3_600)
        }
        val paths = mutableListOf<String>()
        val client = exactUploadClient(tokenManager) { request ->
            paths += request.url.encodedPath
            respond(
                content = """{"error":"unauthorized","message":"expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val authorization = exactAuthorization(transitions.generation.value, "rejected-active")

        val result = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000) {
                transitions.withCurrentGeneration(transitions.generation.value) {
                    DefaultDiagnosticsApi(client).upload(
                        byteArrayOf(1),
                        byteArrayOf(2),
                        capturedProfileId = null,
                        authorization = authorization,
                    )
                }
            }
        }

        val failure = assertIs<DiagnosticsUploadResult.Failure>(result)
        assertEquals(DiagnosticsErrorCode.UNAUTHORIZED, failure.code)
        assertEquals(listOf("/api/v1/diagnostics/reports"), paths)
        assertEquals("rejected-active", tokenManager.getAccessToken())
        assertEquals("refresh-token", tokenManager.getRefreshToken())
    }

    private fun exactUploadClient(
        tokenManager: TokenManagerImpl,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json(SiloJson) }
        install(SiloAuthPlugin) { this.tokenManager = tokenManager }
    }

    private fun exactAuthorization(
        identityGeneration: Long,
        accessToken: String,
    ) = DiagnosticsUploadAuthorization(
        serverId = "server-1",
        serverUrl = "https://silo.example",
        accessToken = accessToken,
        activeProfileId = "active-profile",
        identityGeneration = identityGeneration,
    )

    private suspend fun fixture(
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = if (responseStatus == HttpStatusCode.Created) {
            """{"report_id":"report-1","short_id":"ABC123"}"""
        } else {
            """{
                "status":"disabled",
                "server_instance_id":"server-1",
                "accepted_schema_versions":[1],
                "max_bundle_bytes":10485760,
                "max_manifest_bytes":65536,
                "retention_days":30,
                "consent_notice_version":1
            }""".trimIndent()
        },
        retryAfterSeconds: Long? = null,
        retryAfterHeader: String? = retryAfterSeconds?.toString(),
    ): Fixture {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("access-token", "refresh-token", 3_600)
            setProfileId("active-profile")
            setProfileToken("active-profile-token")
        }
        var capturedRequest: HttpRequestData? = null
        var capturedBody = ""
        val client = HttpClient(
            MockEngine { request ->
                capturedRequest = request
                capturedBody = request.body.toByteArray().decodeToString()
                respond(
                    content = responseBody,
                    status = responseStatus,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        retryAfterHeader?.let { append(HttpHeaders.RetryAfter, it) }
                    },
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
        val api = DefaultDiagnosticsApi(client, nowMs = { NOW_MS })
        return Fixture(
            api = api,
            requestProvider = { capturedRequest },
            bodyProvider = { capturedBody },
        )
    }

    private class Fixture(
        val api: DiagnosticsApi,
        private val requestProvider: () -> HttpRequestData?,
        private val bodyProvider: () -> String,
    ) {
        val request: HttpRequestData? get() = requestProvider()
        val requestBody: String get() = bodyProvider()
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
