package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvMixedLibraryScopeTest {
    @Test fun refillsThreeShelvesAtATimeWithoutReorderingShelvesOrCursors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = owner
        }
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val cursors = mutableMapOf<String, MutableList<String?>>()
        val rows = (0..6).joinToString(",") { id ->
            """{"id":"$id","title":"Shelf $id","featured":${id == 6},"section_type":"recently_added","item_limit":1,"total_count":2,"items":[{"content_id":"film","type":"movie","title":"Film"}]}"""
        }
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/sections") -> """{"sections":[$rows]}"""
                    request.url.encodedPath.endsWith("/filters") -> """{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""
                    else -> {
                        val id = request.url.parameters["section_id"]!!
                        val cursor = request.url.parameters["cursor"]
                        cursors.getOrPut(id) { mutableListOf() } += cursor
                        active++; peak = maxOf(peak, active)
                        try {
                            release.await()
                            delay((6 - id.toInt()) * 10L)
                            if (cursor == null) page("film", "movie", "next") else page("show-$id", "series")
                        } finally { active-- }
                    }
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val sections = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            val vm = TvLibraryDetailViewModel(sections, CatalogRepository(CatalogApi(client)), 7, "Anime", "mixed", "series")
            store.put("series", vm)
            runCurrent()
            assertEquals(3, active, "Three independent shelves should load without waiting on the first")
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(3, peak, "Do not flood the server with every shelf at once")
            assertEquals((0..5).map(Int::toString), vm.uiState.value.sections.map { it.id })
            assertEquals((0..5).map { "show-$it" }, vm.uiState.value.sections.flatMap { it.items }.map { it.contentId })
            assertEquals(6, cursors.size)
            cursors.values.forEach { assertEquals(listOf(null, "next"), it) }
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun seriesShelfRefillsPastMoviesAndBrowseKeepsItsScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = owner
        }
        val sectionCursors = mutableListOf<String?>()
        val browseTypes = mutableListOf<String?>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/sections") -> """{"sections":[{"id":"recent","title":"Recent","section_type":"recently_added","item_limit":1,"total_count":2,"items":[{"content_id":"film","type":"movie","title":"Film"}]}]}"""
                    request.url.encodedPath.endsWith("/filters") -> """{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""
                    request.url.parameters["source"] == "section" -> {
                        assertNull(request.url.parameters["type"], "Sections reject a type overlay")
                        assertNull(request.url.parameters["match"], "Sections reject even the default match overlay")
                        assertEquals("library", request.url.parameters["scope"])
                        assertEquals("7", request.url.parameters["library_id"])
                        assertEquals("recent", request.url.parameters["section_id"])
                        val cursor = request.url.parameters["cursor"]; sectionCursors += cursor
                        if (cursor == null) page("film", "movie", "next") else page("show", "series")
                    }
                    else -> {
                        browseTypes += request.url.parameters["type"]
                        assertEquals("7", request.url.parameters["library_id"])
                        page("show", "series")
                    }
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val sections = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            val vm = TvLibraryDetailViewModel(sections, CatalogRepository(CatalogApi(client)), 7, "Anime", "mixed", "series")
            store.put("series", vm)
            advanceUntilIdle()
            assertEquals(listOf(null, "next"), sectionCursors)
            assertEquals(listOf("show"), vm.uiState.value.sections.single().items.map { it.contentId })
            vm.onTabSelected(TvLibraryTab.Browse); advanceUntilIdle()
            vm.onSortKeySelected(TvLibrarySortOption.Title); advanceUntilIdle()
            assertEquals(listOf<String?>("series", "series"), browseTypes)
            assertEquals(listOf("show"), vm.uiState.value.browseItems.map { it.contentId })
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun scopedPersonalCollectionKeepsLibraryAndTypeWhenSorting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        var requests = 0
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                if (request.url.encodedPath.endsWith("/filters")) {
                    respond("""{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    requests++
                    assertEquals("user_collection", request.url.parameters["source"])
                    assertEquals("series", request.url.parameters["type"])
                    assertEquals("7", request.url.parameters["library_id"])
                    respond(page("show", "series"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }))
        val store = ViewModelStore()
        try {
            val vm = TvLibraryCollectionDetailViewModel(SectionRepository(SectionApi(client)), CatalogRepository(CatalogApi(client)), 7, "c1", "Collection", "series", "user_collection")
            store.put("collection", vm); advanceUntilIdle()
            assertEquals(listOf("show"), vm.uiState.value.items.map { it.contentId })
            vm.onSortSelected(TvLibrarySortOption.Title); advanceUntilIdle()
            assertEquals(2, requests)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun retryCancelsTheSupersededRefillChain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = owner
        }
        val firstPageHeld = CompletableDeferred<Unit>()
        val cursors = mutableListOf<String?>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/sections") -> """{"sections":[{"id":"recent","title":"Recent","section_type":"recently_added","item_limit":1,"total_count":2,"items":[{"content_id":"film","type":"movie","title":"Film"}]}]}"""
                    request.url.encodedPath.endsWith("/filters") -> """{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""
                    else -> {
                        val cursor = request.url.parameters["cursor"]; cursors += cursor
                        // The first load's first page stays in flight until after the retry.
                        if (cursors.size == 1) firstPageHeld.await()
                        if (cursor == null) page("film", "movie", "next") else page("show", "series")
                    }
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val sections = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            val vm = TvLibraryDetailViewModel(sections, CatalogRepository(CatalogApi(client)), 7, "Anime", "mixed", "series")
            store.put("series", vm)
            runCurrent()
            assertEquals(listOf<String?>(null), cursors)
            vm.retryRecommended()
            firstPageHeld.complete(Unit)
            advanceUntilIdle()
            // Only the retry pages on; the superseded load never asks for "next".
            assertEquals(listOf(null, null, "next"), cursors)
            assertEquals(listOf("show"), vm.uiState.value.sections.single().items.map { it.contentId })
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun profileCustomizedShelvesNeverRefillFromTheStoredDefinition() = runTest {
        val film = org.siloserver.silo.model.section.SectionItem("film", "movie", "Film")
        for (row in listOf(
            org.siloserver.silo.model.section.ResolvedSection("random", "random", "Random", itemLimit = 1, totalCount = 21, customized = true, items = listOf(film)),
            org.siloserver.silo.model.section.ResolvedSection("mine", "random", "Mine", itemLimit = 1, totalCount = 21, isCustom = true, items = listOf(film)),
        )) {
            // The refill would page the admin's unfiltered definition, not the profile's override.
            val result = scopeTvLibrarySection(row, "series") { error("Customized shelf must not refill") }
            assertEquals(emptyList(), result.section.items)
            assertTrue(result.incomplete)
        }
    }

    @Test fun personalCollectionsShuffleInTheUserCollectionScope() {
        assertEquals(org.siloserver.silo.model.shuffle.ShuffleScopeKind.USER_COLLECTION, collectionShuffleKind("user_collection", null))
        assertEquals(org.siloserver.silo.model.shuffle.ShuffleScopeKind.LIBRARY_COLLECTION, collectionShuffleKind("library_collection", null))
    }

    @Test fun scopedCollectionViewsOfferNoShuffle() {
        // A shuffle request can't carry the Movies/Series scope, so it would draw from both.
        assertNull(collectionShuffleKind("user_collection", "series"))
        assertNull(collectionShuffleKind("library_collection", "movie"))
    }

    @Test fun seriesShelvesKeepEpisodeProgressAndHideMovies() = runTest {
        val episode = org.siloserver.silo.model.section.SectionItem("episode", "episode", "Episode", positionSeconds = 73.0)
        val row = org.siloserver.silo.model.section.ResolvedSection("continue", "continue_watching", "Continue", itemLimit = 20, totalCount = 2,
            items = listOf(episode, org.siloserver.silo.model.section.SectionItem("film", "movie", "Film")))
        val result = scopeTvLibrarySection(row, "series") {
            ApiResult.Success(org.siloserver.silo.model.catalog.CatalogResponse(items = listOf(
                SiloJson.decodeFromString("""{"content_id":"episode","type":"episode","title":"Episode"}"""),
                SiloJson.decodeFromString("""{"content_id":"film","type":"movie","title":"Film"}"""),
            )))
        }
        assertEquals(listOf(episode), result.section.items, "Inline items keep their richer section payload")
        assertFalse(result.incomplete)
    }

    @Test fun boundedInlineCountStillRefillsScopedShelf() = runTest {
        // Server recently-added shelves report total_count = len(items) after the limit, so 20 inline
        // movies with total_count 20 says nothing about older series further down the source.
        val movies = (1..20).map { org.siloserver.silo.model.section.SectionItem("film-$it", "movie", "Film $it") }
        val row = org.siloserver.silo.model.section.ResolvedSection("recent", "recently_added", "Recent",
            itemLimit = 20, totalCount = 20, items = movies)
        var refills = 0
        val result = scopeTvLibrarySection(row, "series") {
            refills++
            // The source continues past the inline window: the same 20 movies, then an older series.
            val json = movies.map { """{"content_id":"${it.contentId}","type":"movie","title":"${it.title}"}""" } +
                """{"content_id":"show","type":"series","title":"Show"}"""
            ApiResult.Success(org.siloserver.silo.model.catalog.CatalogResponse(items = json.map { SiloJson.decodeFromString(it) }))
        }
        assertEquals(1, refills, "A short scoped slice must ask the catalog instead of trusting total_count")
        assertEquals(listOf("show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    @Test fun pagedEpisodeKeepsItsPlaybackContext() = runTest {
        val row = org.siloserver.silo.model.section.ResolvedSection("continue", "continue_watching", "Continue", totalCount = 1)
        val item = SiloJson.decodeFromString<org.siloserver.silo.model.catalog.BrowseItem>("""
            {"content_id":"episode","type":"episode","title":"Episode","series_id":"show","series_title":"Show",
             "season_number":2,"episode_number":3,"position_seconds":73,"duration_seconds":1200,"item_source":"continue_watching"}
        """)
        val result = scopeTvLibrarySection(row, "series") {
            ApiResult.Success(org.siloserver.silo.model.catalog.CatalogResponse(items = listOf(item)))
        }
        val episode = result.section.items.single()
        assertEquals("show", episode.seriesId)
        assertEquals(2, episode.seasonNumber)
        assertEquals(3, episode.episodeNumber)
        assertEquals(73.0, episode.positionSeconds)
        assertEquals(1200.0, episode.durationSeconds)
        assertEquals("continue_watching", episode.itemSource)
        assertFalse(result.incomplete)
    }

    @Test fun failedRefillRetainsMatchingInlineItemsAndReportsIncomplete() = runTest {
        val episode = org.siloserver.silo.model.section.SectionItem("episode", "episode", "Episode")
        val row = org.siloserver.silo.model.section.ResolvedSection("continue", "continue_watching", "Continue", totalCount = 100, items = listOf(episode))
        val result = scopeTvLibrarySection(row, "series") { ApiResult.Error(503, "unavailable", "Unavailable") }
        assertEquals(listOf(episode), result.section.items)
        assertTrue(result.incomplete)
    }

    private fun page(id: String, type: String, next: String? = null) =
        """{"items":[{"content_id":"$id","type":"$type","title":"$id"}],"page":{"has_more":${next != null},"next_cursor":${next?.let { "\"$it\"" } ?: "null"}},"total":2,"total_exact":true,"window_cursor":"window"}"""
}
