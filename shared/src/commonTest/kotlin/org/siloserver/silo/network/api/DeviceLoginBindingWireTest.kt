package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session-binding fields on the wire (contract §A). */
class DeviceLoginBindingWireTest {
    private val bodies = mutableListOf<Pair<String, JsonObject>>()
    private var capability = """{"revision":"1","state":"available","remote_playback_handoff":false,"protocol_versions":[2]}"""

    private val api = DefaultDeviceLoginApi(
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                (request.body as? TextContent)?.let { bodies += path to Json.parseToJsonElement(it.text).jsonObject }
                val body = when {
                    path.endsWith("/capability") -> capability
                    path.endsWith("/start") -> """{"device_code":"d","user_code":"U","match_code":"m","verification_uri":"v",
                        "verification_uri_complete":"v","expires_at":"2099-01-01T00:00:00Z","expires_in":600,"interval":5,
                        "device_name":"TV","device_platform":"android-tv"}"""
                    else -> """{"status":"pending","poll_after":5,"profile_id":"","profile_token":"","temporary":false}"""
                }
                respond(
                    content = body,
                    status = if (path.endsWith("/start")) HttpStatusCode.Created else HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(SiloJson) } },
        ApiV2Gate.Unrestricted,
    )

    @Test
    fun bindingAndSecretAreSentOnlyWhenGiven() = runTest {
        api.startDeviceLoginAt("https://s.example", "TV", "android-tv")
        api.startDeviceLoginAt("https://s.example", "TV", "android-tv", binding = "B".repeat(43))
        api.pollDeviceLoginAt("https://s.example", "d")
        api.pollDeviceLoginAt("https://s.example", "d", bindingSecret = "S".repeat(43))

        val (plainStart, boundStart, plainPoll, boundPoll) = bodies.map { it.second }
        assertFalse("binding" in plainStart)
        assertEquals("B".repeat(43), boundStart["binding"]?.jsonPrimitive?.content)
        assertFalse("binding_secret" in plainPoll)
        assertEquals("S".repeat(43), boundPoll["binding_secret"]?.jsonPrimitive?.content)
    }

    @Test
    fun capabilityReportsSessionBindingOnlyWhenTheServerSaysSo() = runTest {
        assertFalse(assertIs<ApiResult.Success<*>>(api.deviceLoginCapabilityAt("https://s.example")).let { (it.data as org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse).sessionBinding })
        capability = """{"revision":"1","state":"available","remote_playback_handoff":false,"protocol_versions":[2],"session_binding":true}"""
        assertTrue((assertIs<ApiResult.Success<*>>(api.deviceLoginCapabilityAt("https://s.example")).data as org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse).sessionBinding)
    }

    @Test
    fun lookupCarriesTheBindingWhenPresent() {
        val bound = SiloJson.decodeFromString(DeviceLoginLookupResponse.serializer(), """{"status":"pending","binding":"abc"}""")
        assertEquals("abc", bound.binding)
        val plain = SiloJson.decodeFromString(DeviceLoginLookupResponse.serializer(), """{"status":"pending"}""")
        assertNull(plain.binding)
    }
}
