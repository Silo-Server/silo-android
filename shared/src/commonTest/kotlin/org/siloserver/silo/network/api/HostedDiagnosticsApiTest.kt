package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedDiagnosticsApiTest {
    @Test
    fun dedicatedCollectorFlowUsesOnlyAnonymousCollectorCredentials() = runTest {
        val captured = mutableListOf<CapturedRequest>()
        val engine = MockEngine { request ->
            captured += CapturedRequest(
                method = request.method,
                host = request.url.host,
                path = request.url.encodedPath,
                headers = request.headers,
                contentType = request.body.contentType?.toString(),
                contentLength = request.body.contentLength,
                body = request.body.toByteArray(),
            )
            val (status, body) = when {
                request.url.encodedPath == "/v1/reports/$REPORT_ID" && request.method == HttpMethod.Delete -> {
                    HttpStatusCode.NoContent to ""
                }
                request.url.encodedPath == "/v1/capabilities" -> HttpStatusCode.OK to CAPABILITIES
                request.url.encodedPath == "/v1/installations" -> HttpStatusCode.Created to INSTALLATION
                request.url.encodedPath == "/v1/reports" -> HttpStatusCode.Created to CREATED
                request.url.encodedPath == "/v1/reports/$REPORT_ID/bundle" -> HttpStatusCode.Accepted to PUT_STATUS
                request.url.encodedPath == "/v1/reports/$REPORT_ID" -> HttpStatusCode.OK to STATUS
                else -> HttpStatusCode.NotFound to "{}"
            }
            respond(
                content = body,
                status = status,
                headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
            )
        }
        val transport = createHostedDiagnosticsClient(
            baseUrl = "https://collector.example",
            platformClient = HttpClient(engine),
        )
        val api = DefaultHostedDiagnosticsApi(transport)

        assertIs<HostedDiagnosticsApiResult.Success<*>>(api.capabilities())
        assertIs<HostedDiagnosticsApiResult.Success<*>>(
            api.createInstallation(HostedDiagnosticsInstallationRequest("android", "org.siloserver.silo", "1.2", "34")),
        )
        assertIs<HostedDiagnosticsApiResult.Success<*>>(
            api.createReport(
                installationToken = INSTALLATION_TOKEN,
                request = HostedDiagnosticsCreateReportRequest(
                    reportId = REPORT_ID,
                    manifest = manifest(),
                    bundleBytes = BUNDLE.size.toLong(),
                    bundleSha256 = "a".repeat(64),
                ),
            ),
        )
        val upload = assertIs<HostedDiagnosticsApiResult.Success<HostedDiagnosticsReportStatusResponse>>(
            api.uploadBundle(INSTALLATION_TOKEN, REPORT_ID, UPLOAD_TOKEN, BUNDLE),
        )
        val status = assertIs<HostedDiagnosticsApiResult.Success<HostedDiagnosticsReportStatusResponse>>(
            api.reportStatus(INSTALLATION_TOKEN, REPORT_ID),
        )
        assertIs<HostedDiagnosticsApiResult.Success<Unit>>(
            api.deleteReport(INSTALLATION_TOKEN, REPORT_ID),
        )

        assertEquals(HostedDiagnosticsReportState.PROCESSING, status.value.state)
        assertEquals(REPORT_ID, upload.value.reportId)
        assertEquals("ABC123", upload.value.shortId)
        assertEquals(HostedDiagnosticsReportState.PROCESSING, upload.value.state)
        assertTrue(captured.all { it.host == "collector.example" })
        assertEquals(
            listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Post, HttpMethod.Put, HttpMethod.Get, HttpMethod.Delete),
            captured.map(CapturedRequest::method),
        )
        assertEquals(
            listOf(
                "/v1/capabilities",
                "/v1/installations",
                "/v1/reports",
                "/v1/reports/$REPORT_ID/bundle",
                "/v1/reports/$REPORT_ID",
                "/v1/reports/$REPORT_ID",
            ),
            captured.map(CapturedRequest::path),
        )
        captured.forEachIndexed { index, request ->
            assertNull(request.headers["X-Profile-Id"], "request $index")
            assertNull(request.headers["X-Profile-Token"], "request $index")
            assertNull(request.headers["X-Silo-Device-Id"], "request $index")
            assertNull(request.headers[HttpHeaders.Cookie], "request $index")
            assertTrue(
                request.headers.names().none { it.startsWith("X-Silo-", ignoreCase = true) },
                "request $index must not inherit Silo client headers",
            )
        }
        assertNull(captured[0].headers[HttpHeaders.Authorization])
        assertNull(captured[1].headers[HttpHeaders.Authorization])
        captured.drop(2).forEach { request ->
            assertEquals("Bearer $INSTALLATION_TOKEN", request.headers[HttpHeaders.Authorization])
        }
        assertEquals(UPLOAD_TOKEN, captured[3].headers["X-Upload-Token"])
        assertEquals(BUNDLE.size.toString(), captured[3].headers[HttpHeaders.ContentLength])
        assertEquals(listOf(BUNDLE.size.toString()), captured[3].headers.getAll(HttpHeaders.ContentLength))
        assertEquals(BUNDLE.size.toLong(), captured[3].contentLength)
        assertEquals("application/gzip", captured[3].contentType)
        assertContentEquals(BUNDLE, captured[3].body)

        val envelope = Json.parseToJsonElement(captured[2].body.decodeToString()).jsonObject
        assertEquals(REPORT_ID, envelope.getValue("report_id").jsonPrimitive.content)
        assertEquals("collector-public", envelope.getValue("manifest").jsonObject
            .getValue("destination").jsonObject.getValue("server_instance_id").jsonPrimitive.content)
        assertFalse(envelope.getValue("manifest").jsonObject.getValue("report").jsonObject.containsKey("report_id"))
        val encoded = captured.joinToString("\n") { it.body.decodeToString() }
        listOf(SOURCE_ACCESS, SOURCE_PROFILE, SOURCE_ACCOUNT, SOURCE_SERVER).forEach { sourceIdentity ->
            assertFalse(encoded.contains(sourceIdentity), sourceIdentity)
        }
        transport.close()
    }

    @Test
    fun baseUrlValidationRequiresACanonicalOrigin() {
        listOf(
            "https://collector.example?debug=true",
            "https://collector.example?",
            "https://collector.example/#fragment",
            "https://collector.example#",
            "https://collector.example/v1",
            "https://user:secret@collector.example",
            "http://collector.example",
            "http://localhost.evil",
            "https://collector.example.evil@trusted.example",
            " https://collector.example",
            "https://collector.example ",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { validateHostedDiagnosticsBaseUrl(invalid) }
        }

        assertEquals("https://collector.example", validateHostedDiagnosticsBaseUrl("https://collector.example/"))
        assertEquals("https://collector.example:8443", validateHostedDiagnosticsBaseUrl("https://collector.example:8443"))
        assertEquals("http://localhost:8787", validateHostedDiagnosticsBaseUrl("http://localhost:8787"))
        assertEquals("http://127.0.0.1:8787", validateHostedDiagnosticsBaseUrl("http://127.0.0.1:8787"))
    }

    @Test
    fun crossOriginRedirectCannotReceiveBearerOrRawBundle() = runTest {
        val captured = mutableListOf<CapturedRequest>()
        val transport = createHostedDiagnosticsClient(
            baseUrl = "https://collector.example",
            platformClient = HttpClient(
                MockEngine { request ->
                    captured += CapturedRequest(
                        method = request.method,
                        host = request.url.host,
                        path = request.url.encodedPath,
                        headers = request.headers,
                        contentType = request.body.contentType?.toString(),
                        contentLength = request.body.contentLength,
                        body = request.body.toByteArray(),
                    )
                    respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = Headers.build {
                            append(HttpHeaders.Location, "https://redirect-attacker.example/stolen")
                        },
                    )
                },
            ),
        )

        val result = DefaultHostedDiagnosticsApi(transport).uploadBundle(
            installationToken = INSTALLATION_TOKEN,
            reportId = REPORT_ID,
            uploadToken = UPLOAD_TOKEN,
            bundle = BUNDLE,
        )

        val failure = assertIs<HostedDiagnosticsApiResult.Failure>(result)
        assertEquals(HttpStatusCode.TemporaryRedirect.value, failure.httpStatus)
        assertEquals(1, captured.size)
        assertEquals("collector.example", captured.single().host)
        assertEquals("Bearer $INSTALLATION_TOKEN", captured.single().headers[HttpHeaders.Authorization])
        assertContentEquals(BUNDLE, captured.single().body)
        transport.close()
    }

    @Test
    fun malformedAcceptedUploadReceiptIsAProtocolFailure() = runTest {
        val transport = createHostedDiagnosticsClient(
            baseUrl = "https://collector.example",
            platformClient = HttpClient(
                MockEngine {
                    respond(
                        content = "{not-json",
                        status = HttpStatusCode.Accepted,
                        headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    )
                },
            ),
        )

        val result = assertIs<HostedDiagnosticsApiResult.Failure>(
            DefaultHostedDiagnosticsApi(transport).uploadBundle(
                INSTALLATION_TOKEN,
                REPORT_ID,
                UPLOAD_TOKEN,
                BUNDLE,
            ),
        )

        assertEquals(HttpStatusCode.Accepted.value, result.httpStatus)
        assertEquals("invalid_response", result.errorCode)
        transport.close()
    }

    @Test
    fun deleteFailurePreservesCollectorErrorForLocalRetry() = runTest {
        val transport = createHostedDiagnosticsClient(
            baseUrl = "https://collector.example",
            platformClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"storage_unavailable","message":"try again"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.RetryAfter, "60")
                        },
                    )
                },
            ),
        )

        val result = assertIs<HostedDiagnosticsApiResult.Failure>(
            DefaultHostedDiagnosticsApi(transport).deleteReport(INSTALLATION_TOKEN, REPORT_ID),
        )

        assertEquals(HttpStatusCode.ServiceUnavailable.value, result.httpStatus)
        assertEquals("storage_unavailable", result.errorCode)
        assertEquals(60, result.retryAfterSeconds)
        transport.close()
    }

    @Test
    fun reportNotFoundRemainsAFailureForForeignInstallationOwnership() = runTest {
        val transport = createHostedDiagnosticsClient(
            baseUrl = "https://collector.example",
            platformClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"report_not_found","message":"already erased"}""",
                        status = HttpStatusCode.NotFound,
                        headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    )
                },
            ),
        )

        val result = assertIs<HostedDiagnosticsApiResult.Failure>(
            DefaultHostedDiagnosticsApi(transport).deleteReport(INSTALLATION_TOKEN, REPORT_ID),
        )
        assertEquals(HttpStatusCode.NotFound.value, result.httpStatus)
        assertEquals("report_not_found", result.errorCode)
        transport.close()
    }

    @Test
    fun reportStateMappingPreservesRejectedStateAndError() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"report_id":"$REPORT_ID","short_id":"ABC123","state":"rejected","error_code":"invalid_archive"}""",
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            },
        ) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(org.siloserver.silo.network.SiloJson)
            }
        }
        val result = assertIs<HostedDiagnosticsApiResult.Success<HostedDiagnosticsReportStatusResponse>>(
            DefaultHostedDiagnosticsApi(client).reportStatus(INSTALLATION_TOKEN, REPORT_ID),
        )

        assertEquals(HostedDiagnosticsReportState.REJECTED, result.value.state)
        assertEquals("invalid_archive", result.value.errorCode)
        client.close()
    }

    private fun manifest() = DiagnosticsManifest(
        schemaVersion = 1,
        report = DiagnosticsReport(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-08-11T00:00:00Z",
            captureSessionId = "capture-1",
            appVersion = "1.2",
            appBuild = "34",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "36",
            profileId = null,
        ),
        destination = DiagnosticsDestination("collector-public"),
        consent = DiagnosticsConsent(DiagnosticsConsentMode.MANUAL, 1),
        deviceSummary = DiagnosticsDeviceSummary("Google", "Pixel", "Android 36", "mobile"),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsLogSummary(0, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
        archive = DiagnosticsArchive(listOf("manifest.json", "device.json"), BUNDLE.size.toLong(), 512, "a".repeat(64)),
    )

    private data class CapturedRequest(
        val method: HttpMethod,
        val host: String,
        val path: String,
        val headers: Headers,
        val contentType: String?,
        val contentLength: Long?,
        val body: ByteArray,
    )

    private companion object {
        const val REPORT_ID = "01234567-89ab-4def-8123-456789abcdef"
        const val INSTALLATION_TOKEN = "collector-installation-token"
        const val UPLOAD_TOKEN = "one-time-upload-token"
        const val SOURCE_ACCESS = "silo-access-token"
        const val SOURCE_PROFILE = "source-profile-id"
        const val SOURCE_ACCOUNT = "source-account-id"
        const val SOURCE_SERVER = "https://private-silo.example"
        val BUNDLE = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3, 4)
        val CAPABILITIES = """{
            "status":"available","collector_id":"collector-public","accepted_schema_versions":[1],
            "max_bundle_bytes":10485760,"max_manifest_bytes":65536,"retention_days":30,
            "consent_notice_version":1
        }""".trimIndent()
        val INSTALLATION = """{"installation_id":"install-1","installation_token":"$INSTALLATION_TOKEN"}"""
        val CREATED = """{
            "report_id":"$REPORT_ID","short_id":"ABC123","upload_token":"$UPLOAD_TOKEN",
            "expires_at":"2026-09-10T00:00:00Z"
        }""".trimIndent()
        val PUT_STATUS = """{"report_id":"$REPORT_ID","short_id":"ABC123","state":"processing"}"""
        val STATUS = """{"report_id":"$REPORT_ID","short_id":"ABC123","state":"processing"}"""
    }
}
