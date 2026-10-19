package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.repository.port.HomeCacheSnapshot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On /api/v2 the server drops a whole show when one of its episodes is
 * dismissed from either list, so Home must drop every card of that show, on
 * screen and in the cache the next cold start reads. Movies and books go one
 * card at a time. The removal holds when the re-read that follows fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDismissalSeriesDropTest {
    private val owner = AuthScopeSnapshot("server", "profile", "https://silo.test", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val stamp = "2026-10-18T01:02:03Z"
    private val home = """{"sections":[
        {"id":"cw","section_type":"continue_watching","title":"Continue Watching","items":[
            {"content_id":"ep-a1","type":"episode","title":"A1","series_id":"series-a","progress_updated_at":"$stamp"},
            {"content_id":"ep-b2","type":"episode","title":"B2","series_id":"series-b","item_source":"next_up"},
            {"content_id":"book-1","type":"audiobook","title":"Book 1","series_id":"saga","progress_updated_at":"$stamp"},
            {"content_id":"movie-1","type":"movie","title":"Movie","progress_updated_at":"$stamp"}]},
        {"id":"ip","section_type":"in_progress","title":"In Progress","items":[
            {"content_id":"ep-b1","type":"episode","title":"B1","series_id":"series-b","progress_updated_at":"$stamp"},
            {"content_id":"book-2","type":"audiobook","title":"Book 2","series_id":"saga","progress_updated_at":"$stamp"}]},
        {"id":"nu","section_type":"next_up","title":"Next Up","items":[
            {"content_id":"ep-b2","type":"episode","title":"B2","series_id":"series-b"},
            {"content_id":"ep-c2","type":"episode","title":"C2","series_id":"series-c"}]},
        {"id":"new","section_type":"recently_added","title":"Recently Added","items":[
            {"content_id":"series-b","type":"series","title":"Show B"},
            {"content_id":"ep-b2","type":"episode","title":"B2","series_id":"series-b"}]}]}"""

    @Test
    fun nextUpEpisodeDropsEveryCardOfItsShowFromProgressRowsAndCache() = scenario(
        dismiss = "ep-b2",
        expectedRequest = "next_up/ep-b2 series_id=series-b",
        expectedRows = mapOf(
            "cw" to listOf("ep-a1", "book-1", "movie-1"),
            "ip" to listOf("book-2"),
            "nu" to listOf("ep-c2"),
            "new" to listOf("series-b", "ep-b2"),
        ),
    )

    @Test
    fun audiobookInABookSeriesGoesAlone() = scenario(
        dismiss = "book-1",
        expectedRequest = "continue_watching/book-1 progress_updated_at=$stamp",
        expectedRows = mapOf(
            "cw" to listOf("ep-a1", "ep-b2", "movie-1"),
            "ip" to listOf("ep-b1", "book-2"),
            "nu" to listOf("ep-b2", "ep-c2"),
            "new" to listOf("series-b", "ep-b2"),
        ),
    )

    @Test
    fun progressWinsOverSeriesForTheSurfaceAndLabel() {
        val both = SectionItem(contentId = "e", type = "episode", title = "E", seriesId = "s", progressUpdatedAt = stamp)
        val nextUp = both.copy(progressUpdatedAt = null)
        assertEquals("continue_watching" to "Remove from Continue Watching", HomeDismissal.of(both)!!.let { it.surface to it.menuLabel })
        assertEquals("next_up" to "Remove from Next Up", HomeDismissal.of(nextUp)!!.let { it.surface to it.menuLabel })
        assertEquals(null, HomeDismissal.of(nextUp.copy(seriesId = " ")))
    }

    private fun scenario(dismiss: String, expectedRequest: String, expectedRows: Map<String, List<String>>) = runTest(dispatcher) {
        val requests = mutableListOf<String>()
        var reads = 0
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = this@HomeDismissalSeriesDropTest.dispatcher
            addHandler { request ->
                if (request.method == HttpMethod.Put) {
                    val body = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject.entries
                        .joinToString(" ") { (key, value) -> "$key=${value.jsonPrimitive.content}" }
                    requests += request.url.encodedPath.removePrefix("/api/v2/home/dismissals/") + " " + body
                    respond("", HttpStatusCode.NoContent)
                } else if (reads++ == 0) {
                    respond(home, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    // The re-read after the dismissal fails; the removal must hold.
                    respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val cache = MemoryHomeCache()
        val store = ViewModelStore()
        try {
            val vm = HomeViewModel(
                SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted, decodeDispatcher = dispatcher))),
                MediaActionsCoordinator(PersonalDataRepository(PersonalDataApi(client))),
                homeCache = cache,
            )
            store.put("home", vm)
            val item = vm.uiState.value.sections.first().items.single { it.contentId == dismiss }

            vm.dismissFromHome(item)

            assertEquals(listOf(expectedRequest), requests)
            assertEquals(2, reads)
            assertEquals(expectedRows, vm.uiState.value.sections.rows())
            assertEquals(expectedRows, cache.sections?.rows())
        } finally { store.clear(); client.close() }
    }

    private fun List<ResolvedSection>.rows() = associate { section -> section.id to section.items.map { it.contentId } }

    private class MemoryHomeCache : HomeCachePort {
        var sections: List<ResolvedSection>? = null

        override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?, stillCurrent: () -> Boolean) {
            if (stillCurrent()) this.sections = sections
        }

        override suspend fun getCachedHomeV2(owner: AuthScopeSnapshot) = sections?.let { HomeCacheSnapshot(it, 0) }
    }
}
