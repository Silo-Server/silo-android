package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.download.DownloadManifest
import org.siloserver.silo.model.download.effectiveMarkerSegments
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.SiloDeviceMetadata
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadManifestV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private var deviceId = "device"
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = scope
    }
    private val devices = object : DeviceMetadataProvider {
        override suspend fun current() = SiloDeviceMetadata(deviceId, "test", "android")
    }
    private val minimal = """{"download_id":"download","media_file_id":"7"}"""

    @Test
    fun manifestReadPreservesAllOccurrencesAndPinsRequestOwner() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertEquals("/api/v2/downloads/download/manifest", it.url.encodedPath)
            respond(
                """{
                    "download_id":"download", "media_file_id":"7", "duration_seconds":200,
                    "marker_segments":[
                        {"kind":"recap","start_seconds":0,"end_seconds":10},
                        {"kind":"intro","start_seconds":10,"end_seconds":20},
                        {"kind":"intro","start_seconds":30,"end_seconds":40},
                        {"kind":"credits","start_seconds":150,"end_seconds":160},
                        {"kind":"preview","start_seconds":170,"end_seconds":180},
                        {"kind":"credits","start_seconds":190,"end_seconds":200}
                    ]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val result = assertIs<ApiResult.Success<DownloadManifest>>(
                DownloadRegistryV2Api(client, tokens, devices, ApiV2Gate.Unrestricted).manifest("download", 7, scope),
            ).data
            assertEquals(200.0, result.durationSeconds)
            assertEquals(listOf("recap", "intro", "intro", "credits", "preview", "credits"),
                result.effectiveMarkerSegments()?.map { it.kind })
        } finally {
            client.close()
        }
    }

    @Test
    fun manifestRejectsMismatchedOrNoncanonicalIdentity() = runTest {
        for (body in listOf(
            minimal.replace("download\"", "other\""),
            minimal.replace("\"7\"", "\"8\""),
            minimal.replace("\"7\"", "7"),
            minimal.replace("\"7\"", "\"07\""),
            minimal.replace("\"7\"", "\"2147483648\""),
        )) {
            val client = HttpClient(MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(SiloJson) } }
            try {
                assertIs<ApiResult.Error>(
                    DownloadRegistryV2Api(client, tokens, devices, ApiV2Gate.Unrestricted).manifest("download", 7, scope),
                    body,
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun manifestRejectsResponseAfterProfileOrDeviceChanges() = runTest {
        for (changeDevice in listOf(false, true)) {
            val owner = scope
            val client = HttpClient(MockEngine {
                if (changeDevice) deviceId = "replacement" else scope = scope.copy(profileId = "other-profile")
                respond(minimal, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(SiloJson) } }
            try {
                val error = assertIs<ApiResult.Error>(
                    DownloadRegistryV2Api(client, tokens, devices, ApiV2Gate.Unrestricted).manifest("download", 7, owner),
                )
                assertEquals("identity_changed", error.error)
            } finally {
                client.close()
                scope = owner
                deviceId = "device"
            }
        }
    }
}
