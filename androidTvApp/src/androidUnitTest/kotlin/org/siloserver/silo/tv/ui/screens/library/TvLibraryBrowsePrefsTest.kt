package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.common.settings.BrowsePrefsStore
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
// BrowsePrefsStore writes through real SharedPreferences.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvLibraryBrowsePrefsTest {

    @Test
    fun savedStateRoundTripsEveryFacetAndDropsPerVisitFields() {
        var selection = TvCatalogFacetSelection(matchAll = false)
        for (facet in TvCatalogFacet.entries) {
            val value = when (facet) {
                TvCatalogFacet.Decade -> "1990"
                TvCatalogFacet.DynamicRange -> "dolby_vision"
                TvCatalogFacet.WatchStatus -> TvWatchStatusFilter.InProgress.wireValue
                else -> "${facet.name}-value"
            }
            selection = selection.toggled(facet, value)
        }
        val filter = TvLibraryBrowseFilter(
            genre = "Drama",
            namePrefix = "K",
            sort = TvLibrarySortOption.Rating.wireValue,
            order = "asc",
            facetSelection = selection,
        )

        val saved = filter.toSavedState()
        // The shared model (phone + Apple) spells In Progress `inProgress`.
        assertEquals(setOf("inProgress"), saved.valuesFor(CatalogFacet.WatchStatus))
        assertEquals(CatalogFacet.entries.size, saved.selections.size)

        assertEquals(
            TvLibraryBrowseFilter(sort = "rating_imdb", order = "asc", facetSelection = selection),
            saved.toTvBrowseFilter(),
        )
    }

    @Test
    fun browseSortAndFiltersSurviveANewViewModel() = runPrefsTest {
        val prefs = BrowsePrefsStore(ApplicationProvider.getApplicationContext(), FakeServerRegistry(), "androidtv")
        val first = viewModel(prefs)
        first.onTabSelected(TvLibraryTab.Browse)
        first.onSortKeySelected(TvLibrarySortOption.ReleaseDate)
        first.onFacetSelectionApplied(
            TvCatalogFacetSelection().toggled(TvCatalogFacet.Genre, "Drama"),
        )
        first.onNamePrefixChanged("K")

        // A cold start lands on Recommended; opening Browse restores the
        // saved sort and filters but not the A–Z jump.
        val second = viewModel(prefs)
        assertEquals(TvLibraryBrowseFilter(), second.uiState.value.browseFilter)
        second.onTabSelected(TvLibraryTab.Browse)
        val restored = second.uiState.value.browseFilter
        assertEquals("release_date", restored.sort)
        assertEquals("desc", restored.order)
        assertEquals(setOf("Drama"), restored.facetSelection.genres)
        assertNull(restored.namePrefix)

        // Turning preserve off forgets them: Browse opens on Title A–Z again.
        second.onPreserveFiltersChanged(false)
        val third = viewModel(prefs)
        assertEquals(false, third.uiState.value.preserveFilters)
        third.onTabSelected(TvLibraryTab.Browse)
        assertEquals("title", third.uiState.value.browseFilter.sort)
        assertEquals(TvCatalogFacetSelection(), third.uiState.value.browseFilter.facetSelection)
    }

    @Test
    fun presetSectionsDoNotOverwriteTheSavedBrowseState() = runPrefsTest {
        val prefs = BrowsePrefsStore(ApplicationProvider.getApplicationContext(), FakeServerRegistry(), "androidtv")
        val first = viewModel(prefs)
        first.onTabSelected(TvLibraryTab.Browse)
        first.onSortKeySelected(TvLibrarySortOption.Year)
        first.onTabSelected(TvLibraryTab.RecentlyAdded)
        first.onTabSelected(TvLibraryTab.Alphabet)

        assertEquals("year", prefs.savedState(LIBRARY_ID)?.sort)
    }

    @Test
    fun clearingFiltersReplacesARestoredFilterButKeepsTheSort() = runPrefsTest {
        val prefs = BrowsePrefsStore(ApplicationProvider.getApplicationContext(), FakeServerRegistry(), "androidtv")
        val first = viewModel(prefs)
        first.onTabSelected(TvLibraryTab.Browse)
        first.onSortKeySelected(TvLibrarySortOption.Year)
        first.onFacetSelectionApplied(
            TvCatalogFacetSelection().toggled(TvCatalogFacet.WatchStatus, TvWatchStatusFilter.Unwatched.wireValue),
        )

        // The Browse error screen's Clear filters action, after a restart.
        val second = viewModel(prefs)
        second.onTabSelected(TvLibraryTab.Browse)
        second.onFacetSelectionApplied(TvCatalogFacetSelection())

        val saved = prefs.savedState(LIBRARY_ID)
        assertEquals(emptyMap(), saved?.selections)
        assertEquals("year", saved?.sort)
    }

    @Test
    fun mixedLibraryMoviesAndSeriesKeepSeparateBrowseState() = runPrefsTest {
        val prefs = BrowsePrefsStore(ApplicationProvider.getApplicationContext(), FakeServerRegistry(), "androidtv")
        val movies = viewModel(prefs, libraryType = "mixed", mediaScope = "movie")
        movies.onTabSelected(TvLibraryTab.Browse)
        movies.onSortKeySelected(TvLibrarySortOption.Year)
        movies.onFacetSelectionApplied(
            TvCatalogFacetSelection().toggled(TvCatalogFacet.Genre, "Drama"),
        )
        movies.onPreserveFiltersChanged(false)

        // The Series tab of the same library opens on its own defaults and
        // keeps its own preserve toggle.
        val series = viewModel(prefs, libraryType = "mixed", mediaScope = "series")
        assertEquals(true, series.uiState.value.preserveFilters)
        series.onTabSelected(TvLibraryTab.Browse)
        assertEquals("title", series.uiState.value.browseFilter.sort)
        assertEquals(TvCatalogFacetSelection(), series.uiState.value.browseFilter.facetSelection)
        series.onSortKeySelected(TvLibrarySortOption.Rating)

        assertEquals("rating_imdb", prefs.savedState(LIBRARY_ID, "series")?.sort)
        assertNull(prefs.savedState(LIBRARY_ID, "movie"))
        assertNull(prefs.savedState(LIBRARY_ID))
        assertEquals(false, viewModel(prefs, libraryType = "mixed", mediaScope = "movie").uiState.value.preserveFilters)
    }

    private val createdViewModels = mutableListOf<TvLibraryDetailViewModel>()

    private fun runPrefsTest(block: suspend () -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            createdViewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            createdViewModels.clear()
            Dispatchers.resetMain()
        }
    }

    private suspend fun viewModel(
        prefs: BrowsePrefsStore,
        libraryType: String = "movies",
        mediaScope: String? = null,
    ): TvLibraryDetailViewModel {
        val client = HttpClient(
            MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/api/v1/library/$LIBRARY_ID/sections" -> """{"sections":[]}"""
                    "/api/v2/catalog/filters" -> """{"genres":["Drama"]}"""
                    else -> """{"total":0,"total_exact":true,"page":{"has_more":false},"items":[]}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val viewModel = TvLibraryDetailViewModel(
            sectionRepository = SectionRepository(SectionApi(client)),
            catalogRepository = CatalogRepository(CatalogApi(client)),
            libraryId = LIBRARY_ID,
            libraryTitle = "Movies",
            libraryType = libraryType,
            mediaScope = mediaScope,
            browsePrefs = prefs,
        ).also { createdViewModels += it }
        // Let the eager Recommended load finish before the test drives tabs.
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                while (viewModel.uiState.value.recommendedLoading) delay(10)
            }
        }
        return viewModel
    }

    /** BrowsePrefsStore persists nothing without an active server + profile. */
    private class FakeServerRegistry : ServerRegistry {
        private val entry = ServerEntry(id = "server-1", url = "https://silo.test", profileId = "profile-1")
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry))
        override val activeServerId: StateFlow<String?> = MutableStateFlow(entry.id)
        override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(entry)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = entry.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    private companion object {
        const val LIBRARY_ID = 7
    }
}
