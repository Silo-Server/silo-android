package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The bandwidth probe download and its report, from the buffering-quality
 * spec's *Probe on Android and Apple*: the advertised URL is the server's own
 * and authenticated, and the short-lived proxy URL it redirects to gets none
 * of this app's credentials.
 */
class PlaybackBandwidthProbeApiTest {
    private data class Seen(val url: String, val authorization: String?, val profileId: String?, val body: String)

    private val scope = AuthScopeSnapshot(
        serverId = "server-a",
        profileId = "profile-a",
        serverUrl = "https://silo.example",
        profileToken = "ptoken-a",
    )

    private suspend fun api(seen: MutableList<Seen>, answer: (String) -> Triple<HttpStatusCode, String, Map<String, String>>): PlaybackV2Api {
        val tokens = TokenManagerImpl().apply { saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600) }
        val client = HttpClient(
            MockEngine { request ->
                seen += Seen(
                    request.url.toString(),
                    request.headers[HttpHeaders.Authorization],
                    request.headers["X-Profile-Id"],
                    request.body.toByteArray().decodeToString(),
                )
                val (status, body, headers) = answer(request.url.toString())
                respond(body, status, headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray()))
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        return PlaybackV2Api(client, ApiV2Gate.Unrestricted)
    }

    private suspend fun readAll(response: BandwidthProbeResponseV2): Any = when (response) {
        is BandwidthProbeResponseV2.Body -> {
            val buffer = ByteArray(16)
            var total = 0
            while (true) {
                val count = response.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
        else -> response
    }

    @Test
    fun theProbeUrlCarriesAuthAndTheProxyItRedirectsToDoesNot() = runTest {
        val seen = mutableListOf<Seen>()
        val probe = "https://silo.example/api/v2/playback/bandwidth-probe?media_path=true&size=40000000"
        val proxy = "https://node.example:8443/stream/bandwidth-probe/opaque?size=40000000"
        val api = api(seen) { url ->
            if (url == probe) Triple(HttpStatusCode.TemporaryRedirect, "", mapOf(HttpHeaders.Location to proxy))
            else Triple(HttpStatusCode.OK, "0123456789abcdef0123", emptyMap())
        }

        assertEquals(20, api.bandwidthProbe(scope, probe, ::readAll))

        assertEquals(listOf(probe, proxy), seen.map { it.url })
        assertEquals("Bearer ACCESS-A", seen[0].authorization)
        assertEquals("profile-a", seen[0].profileId)
        assertNull(seen[1].authorization)
        assertNull(seen[1].profileId)
    }

    @Test
    fun aRateLimitWaitsItsRetryAfterThirtySecondsWithoutAReadableOneAndAtLeastOne() = runTest {
        val cases = listOf("42" to 42, null to 30, "Wed, 21 Oct 2026 07:28:00 GMT" to 30, "1.5" to 30, "0" to 1, "-4" to 1)
        for ((header, seconds) in cases) {
            val api = api(mutableListOf()) {
                Triple(HttpStatusCode.TooManyRequests, "{}", header?.let { mapOf(HttpHeaders.RetryAfter to it) }.orEmpty())
            }
            assertEquals(
                BandwidthProbeResponseV2.RateLimited(seconds),
                api.bandwidthProbe(scope, "https://silo.example/p", ::readAll),
                header,
            )
        }
    }

    @Test
    fun anUnavailableMediaPathIsNotMeasured() = runTest {
        val seen = mutableListOf<Seen>()
        val api = api(seen) { Triple(HttpStatusCode.ServiceUnavailable, "{}", emptyMap()) }
        assertEquals(BandwidthProbeResponseV2.Unavailable(503), api.bandwidthProbe(scope, "https://silo.example/p", ::readAll))
        assertEquals(1, seen.size)
    }

    @Test
    fun theResultIsReportedAsTheContractNamesIt() = runTest {
        val seen = mutableListOf<Seen>()
        val api = api(seen) { Triple(HttpStatusCode.NoContent, "", emptyMap()) }

        assertIs<org.siloserver.silo.network.ApiResult.Success<Unit>>(
            api.reportBandwidthProbe(scope, PlaybackBandwidthProbeResultV2(48_000, 12_000_000, 2_000, "wifi")),
        )
        val sent = seen.single()
        assertEquals("https://silo.example/api/v2/playback/bandwidth-probe/results", sent.url)
        assertEquals("Bearer ACCESS-A", sent.authorization)
        assertEquals(
            SiloJson.parseToJsonElement("""{"kbps":48000,"bytes":12000000,"duration_ms":2000,"transport":"wifi"}"""),
            SiloJson.parseToJsonElement(sent.body),
        )
    }
}
