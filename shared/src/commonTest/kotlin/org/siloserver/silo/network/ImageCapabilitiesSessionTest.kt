package org.siloserver.silo.network

import kotlinx.serialization.json.decodeFromJsonElement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.api.ImageCapabilities
import org.siloserver.silo.network.api.ImageCapabilitiesApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.apiv2.ApiV2Fixtures
import org.siloserver.silo.repository.ArtworkLayout
import org.siloserver.silo.repository.ArtworkSizing
import org.siloserver.silo.repository.ArtworkSurface
import org.siloserver.silo.repository.ImageCapabilitiesSession
import org.siloserver.silo.repository.RecommendationRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.network.api.RecommendationApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.apiv2.DiscoverV2Api
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.network.apiv2.SimilarCardsV2Api
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageCapabilitiesSessionTest {
    @Test
    fun optionalAndFutureCapabilityFieldsDecode() {
        assertEquals(ImageCapabilities(), SiloJson.decodeFromString<ImageCapabilities>("{}"))
        assertEquals(ImageCapabilities(sizes = listOf("large")), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":null,"delivery":null,"sizes":["large"]}"""))
        assertEquals(ImageCapabilities("local", "server"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"local","delivery":"server"}"""))
        assertEquals(ImageCapabilities("s3", "direct"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"s3","delivery":"direct"}"""))
        assertEquals(ImageCapabilities("future", "future"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"future","delivery":"future"}"""))
    }

    @Test
    fun slotWidthsAreAcceptedOnlyWhenAdvertisedAndAvailable() {
        val fixture = ApiV2Fixtures.bodyObject("get_image_capabilities_ok")
        val current = SiloJson.decodeFromJsonElement<ImageCapabilities>(fixture)
        assertEquals(setOf("poster", "backdrop", "logo", "still", "photo"), current.acceptedWidthSlots)
        assertEquals("backdrop_width", current.slotWidthParams?.get("backdrop"))
        assertEquals(listOf("small", "medium", "large", "original"), current.sizes)
        val older = ApiV2Fixtures.with(fixture, "slot_width_params" to null)
        assertEquals(emptySet(), SiloJson.decodeFromJsonElement<ImageCapabilities>(older).acceptedWidthSlots)
        val unavailable = ApiV2Fixtures.with(fixture, "state" to JsonPrimitive("unavailable"))
        assertEquals(emptySet(), SiloJson.decodeFromJsonElement<ImageCapabilities>(unavailable).acceptedWidthSlots)
    }

    @Test
    fun sizingSendsOnlyAdvertisedSlotsAndKeysCachesByLayout() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        var body = """{"state":"available","slot_width_params":{"poster":"poster_width","backdrop":"backdrop_width"}}"""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val layout = object : ArtworkLayout {
            override fun widths(surface: ArtworkSurface, cardScale: Float) =
                ArtworkWidths(poster = (176 * cardScale).toInt(), backdrop = 1344, logo = 880)
            override fun cardScale() = 1.5f
        }
        val sizing = ArtworkSizing(layout, session)
        val job = session.start(backgroundScope)
        try {
            runCurrent()
            // Requests use the active card size; cache keys stay at the standard size.
            val sized = sizing.request(ArtworkSurface.HOME)
            assertEquals("poster_width=264&backdrop_width=1344", sized?.key)
            assertEquals("a", sized?.serverId)
            assertEquals("home@poster_width=176&backdrop_width=1344&logo_width=880", sizing.cacheKey("home", ArtworkSurface.HOME))

            // An older server keeps today's request, but the cache key still names the layout.
            body = """{"storage_backend":"local","delivery":"server"}"""
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) { tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test", identityGeneration = 1) }
            runCurrent()
            assertNull(sizing.request(ArtworkSurface.HOME))
            assertEquals("home@poster_width=176&backdrop_width=1344&logo_width=880", sizing.cacheKey("home", ArtworkSurface.HOME))
            assertNull(ArtworkSizing.None.request(ArtworkSurface.HOME))
            assertEquals("home", ArtworkSizing.None.cacheKey("home", ArtworkSurface.HOME))
        } finally { job.cancel(); client.close() }
    }

    /**
     * A server without per-slot widths answers 422 to any width parameter, so
     * reads send them only when the capability lists slot_width_params, and
     * recommendation reads never send image_size.
     */
    @Test
    fun readsSendWidthsOnlyToAServerThatAdvertisesThem() = runTest {
        suspend fun readsAgainst(capabilities: String): Map<String, Map<String, String>> {
            val barrier = DefaultIdentityTransitionBarrier()
            val tokens = ImageTokens()
            val sent = mutableMapOf<String, Map<String, String>>()
            val client = HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = when {
                        path == "/api/v2/images/capabilities" -> capabilities
                        path == "/api/v2/home/sections" -> """{"sections":[]}"""
                        else -> """{"items":[]}"""
                    }
                    if (path != "/api/v2/images/capabilities") {
                        sent[path] = request.url.parameters.names()
                            .filter { it.endsWith("_width") || it == "image_size" }
                            .associateWith { request.url.parameters[it]!! }
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            })) { install(SiloAuthPlugin) { tokenManager = tokens } }
            val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
            val sizing = ArtworkSizing(ArtworkLayout { _, _ -> ArtworkWidths(poster = 176, backdrop = 1344, logo = 880) }, session)
            val gate = ApiV2Gate.Unrestricted
            val recommendations = RecommendationRepository(RecommendationApi(client,
                similar = SimilarCardsV2Api(client, tokens, gate), discover = DiscoverV2Api(client, tokens, gate)), sizing)
            val sections = SectionRepository(SectionApi(client,
                home = HomeSectionsV2Api(client, tokens, gate, StandardTestDispatcher(testScheduler))), artwork = sizing)
            val job = session.start(backgroundScope)
            try {
                runCurrent()
                val owner = tokens.owner!!
                recommendations.getDiscover(owner)
                recommendations.loadSimilarCards("m", owner, { true }) {}
                sections.getHomeSections(owner)
            } finally { job.cancel(); client.close() }
            return sent
        }

        val older = readsAgainst("""{"storage_backend":"local","delivery":"server","state":"available","sizes":["small","medium","large","original"]}""")
        assertEquals(setOf("/api/v2/recommendations/discover", "/api/v2/recommendations/similar/m", "/api/v2/home/sections"), older.keys)
        older.forEach { (path, params) -> assertEquals(emptyMap(), params, path) }

        val current = readsAgainst(ApiV2Fixtures.body("get_image_capabilities_ok"))
        val hero = mapOf("poster_width" to "176", "backdrop_width" to "1344", "logo_width" to "880")
        assertEquals(hero, current["/api/v2/recommendations/discover"])
        assertEquals(hero, current["/api/v2/recommendations/similar/m"])
        assertEquals(hero, current["/api/v2/home/sections"])
    }

    /**
     * A server switch swaps the tokens before the probe for the new server
     * starts. Widths sized for the old server must not reach the new one,
     * which may reject them.
     */
    @Test
    fun widthsNeverFollowAServerSwitch() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val releaseB = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val body = if (request.url.host == "a.test") {
                    """{"state":"available","slot_width_params":{"poster":"poster_width"}}"""
                } else {
                    releaseB.await()
                    """{"storage_backend":"local","delivery":"server"}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val sizing = ArtworkSizing(ArtworkLayout { _, _ -> ArtworkWidths(poster = 176) }, session)
        val job = session.start(backgroundScope)
        try {
            runCurrent()
            val forA = sizing.request(ArtworkSurface.HOME)
            assertEquals("poster_width=176", forA?.key)
            // A token refresh moves the credential epoch but not the server; widths stay, without waiting.
            tokens.owner = tokens.owner!!.copy(credentialEpoch = 7)
            val refreshStart = testScheduler.currentTime
            assertEquals("poster_width=176", sizing.request(ArtworkSurface.HOME)?.key)
            assertEquals(refreshStart, testScheduler.currentTime)

            // The tokens point at B before B's probe has answered.
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) {
                tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test", identityGeneration = 1)
            }
            // A read for B neither reuses A's answer nor waits past the timeout.
            assertNull(sizing.request(ArtworkSurface.HOME))
            // Widths A advertised are dropped from a request made for B.
            val sent = HttpRequestBuilder().apply { artworkWidths(forA, tokens.owner) }
            assertEquals(null, sent.url.parameters["poster_width"])
            val toA = HttpRequestBuilder().apply { artworkWidths(forA, tokens.owner!!.copy(serverId = "a")) }
            assertEquals("176", toA.url.parameters["poster_width"])

            releaseB.complete(Unit)
            runCurrent()
            assertNull(sizing.request(ArtworkSurface.HOME))
        } finally { job.cancel(); client.close() }
    }

    @Test
    fun anUnreachableServerIsProbedAgain() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        var reachable = false
        var probes = 0
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                probes++
                if (!reachable) throw RuntimeException("offline")
                respond("""{"state":"available","slot_width_params":{"poster":"poster_width"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val job = session.start(backgroundScope)
        try {
            runCurrent()
            // The failure settles at once, so reads don't keep waiting for it.
            assertNull(session.settled(1.seconds)?.capabilities)
            reachable = true
            advanceTimeBy(6.seconds)
            runCurrent()
            assertEquals(setOf("poster"), session.capabilities.value?.acceptedWidthSlots)
            assertEquals(2, probes)
        } finally { job.cancel(); client.close() }
    }

    @Test
    fun widthRequestsWaitBrieflyForTheProbe() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                release.await()
                respond("""{"state":"available","slot_width_params":{"poster":"poster_width"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val sizing = ArtworkSizing(ArtworkLayout { _, _ -> ArtworkWidths(poster = 176) }, session)
        val job = session.start(backgroundScope)
        try {
            // A probe that never answers lets the request go without widths after the timeout,
            // and the answer isn't cached: it's sized for no layout.
            assertNull(session.settled(1.seconds))
            assertEquals(false, sizing.mayCache(sizing.request(ArtworkSurface.HOME)))
            val waiting = async { session.settled(10.seconds) }
            runCurrent()
            release.complete(Unit)
            assertEquals(setOf("poster"), waiting.await()?.capabilities?.acceptedWidthSlots)
            assertEquals(true, sizing.mayCache(sizing.request(ArtworkSurface.HOME)))
            // Once the check has answered, a read without widths is one the server does not size.
            assertEquals(true, sizing.mayCache(null))
        } finally { job.cancel(); client.close() }
    }

    @Test
    fun startupSwitchAndSignOutKeepProbeInItsSession() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requested += request.url.host
                assertEquals("GET", request.method.value)
                assertEquals("/api/v2/images/capabilities", request.url.encodedPath)
                assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
                respond(if (request.url.host == "a.test") """{"storage_backend":"local","delivery":"server"}""" else "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val job = session.start(backgroundScope)
        try {
            runCurrent()
            assertEquals(ImageCapabilities("local", "server"), session.capabilities.value)
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) { tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test/base", identityGeneration = 1) }
            runCurrent()
            assertEquals(ImageCapabilities(), session.capabilities.value)
            barrier.changing(IdentityTransitionKind.SIGN_OUT) { tokens.owner = null }
            runCurrent()
            assertNull(session.capabilities.value)
            assertEquals(listOf("a.test", "b.test"), requested)
        } finally { job.cancel(); client.close() }
    }

    @Test
    fun lateProbeAndUnavailableEndpointCannotCarryCapabilitiesAcrossServers() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.host == "a.test") {
                    started.complete(Unit)
                    release.await()
                    respond("""{"storage_backend":"local","delivery":"server"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val job = session.start(backgroundScope)
        try {
            started.await()
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) { tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test", identityGeneration = 1) }
            release.complete(Unit)
            runCurrent()
            assertNull(session.capabilities.value)
        } finally { job.cancel(); client.close() }
    }
}

private class ImageTokens : TokenManager {
    var owner: AuthScopeSnapshot? = AuthScopeSnapshot("a", null, "https://a.test/base", null, isIdentityGenerationStamped = true)
    override suspend fun snapshotCurrentScope() = owner
    override suspend fun getAccessToken(): String? = owner?.let { "test-access" }
    override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? = if (scope.isSameIdentityAs(owner)) "test-access" else null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = owner?.serverUrl.orEmpty()
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = owner?.serverId
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
    override val sessionExpired = MutableSharedFlow<Unit>()
}
