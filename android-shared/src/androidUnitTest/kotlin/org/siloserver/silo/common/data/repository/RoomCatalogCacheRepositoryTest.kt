package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.repository.ArtworkLayout
import org.siloserver.silo.repository.ArtworkSizing
import org.siloserver.silo.repository.ArtworkSurface
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RoomCatalogCacheRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var scope: AuthScopeSnapshot? = AuthScopeSnapshot("s1", "p1", "https://s1.example", null)
    private val repo = RoomCatalogCacheRepository(db = db, snapshotProvider = { scope }, now = { 1000L })

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun scopedV2SectionsExcludeLegacyAndReplacementAuthority() = runTest {
        val owner = scope!!
        val sections = listOf(org.siloserver.silo.model.section.ResolvedSection("row", "custom", "Row"))
        repo.cacheLibrarySections(7, sections)
        assertNull(repo.getCachedLibrarySectionsV2(7, owner))
        repo.cacheLibrarySectionsV2(7, sections, owner)
        assertEquals(sections, repo.getCachedLibrarySectionsV2(7, owner))
        assertNull(repo.getCachedLibrarySectionsV2(8, owner))
        scope = owner.copy(profileToken = "new")
        assertNull(repo.getCachedLibrarySectionsV2(7, scope!!))
        assertNull(repo.getCachedLibrarySectionsV2(7, owner))
        repo.cacheLibrarySectionsV2(7, sections, owner)
        assertNull(repo.getCachedLibrarySectionsV2(7, scope!!))
        scope = owner.copy(credentialEpoch = 2)
        assertNull(repo.getCachedLibrarySectionsV2(7, scope!!))
    }

    @Test
    fun artworkReadsAreKeyedByTheWidthsTheyAreDrawnAt() = runTest {
        var widths = ArtworkWidths(backdrop = 1344, still = 360)
        val layout = ArtworkLayout { surface, _ ->
            if (surface == ArtworkSurface.SEASON_EPISODES) ArtworkWidths(still = 460) else widths
        }
        val sized = RoomCatalogCacheRepository(db = db, snapshotProvider = { scope }, now = { 1000L },
            artwork = ArtworkSizing(layout, null))
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "series", title = "Show")
        val episodes = org.siloserver.silo.model.catalog.EpisodesResponse()
        sized.cacheItemDetail("c1", detail)
        sized.cacheEpisodes("c1", 1, episodes, CatalogCacheWriteLease(0L), ArtworkSurface.EPISODES)
        assertEquals(detail, sized.getCachedItemDetail("c1"))
        assertEquals(episodes, sized.getCachedEpisodes("c1", 1, ArtworkSurface.EPISODES))
        // A season's page draws the stills wider, so it keeps its own copy.
        assertNull(sized.getCachedEpisodes("c1", 1, ArtworkSurface.SEASON_EPISODES))
        // Another layout misses rather than serving artwork sized for this one.
        widths = ArtworkWidths(backdrop = 2560, still = 480)
        assertNull(sized.getCachedItemDetail("c1"))
        // The language purge still matches width-keyed rows.
        widths = ArtworkWidths(backdrop = 1344, still = 360)
        sized.invalidateDetailMetadata()
        assertNull(sized.getCachedItemDetail("c1"))
        assertNull(sized.getCachedEpisodes("c1", 1, ArtworkSurface.EPISODES))
    }

    @Test
    fun aReadIsCachedUnderTheLayoutItWasSizedFor() = runTest {
        val open = ArtworkWidths(backdrop = 2152)
        val folded = ArtworkWidths(backdrop = 2423)
        var widths = open
        val artwork = ArtworkSizing({ _, _ -> widths }, null)
        val sized = RoomCatalogCacheRepository(db = db, snapshotProvider = { scope }, now = { 1000L }, artwork = artwork)
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "movie", title = "Movie")
        // The read starts open; the device folds before its answer is cached.
        val captured = artwork.cacheFingerprint(ArtworkSurface.DETAIL)
        widths = folded
        sized.cacheItemDetail("c1", detail, CatalogCacheWriteLease(0L, captured))
        assertNull(sized.getCachedItemDetail("c1"))
        widths = open
        assertEquals(detail, sized.getCachedItemDetail("c1"))
    }

    @Test
    fun rowsCachedBeforeArtworkWidthsAreReadUntilReplaced() = runTest {
        // An earlier app version cached under the bare key.
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "movie", title = "Before")
        val episodes = org.siloserver.silo.model.catalog.EpisodesResponse()
        val page = CatalogResponse(total = 1, items = listOf(BrowseItem(contentId = "c1", type = "movie", title = "A")))
        repo.cacheItemDetail("c1", detail)
        repo.cacheEpisodes("s1", 1, episodes)
        repo.cacheDefaultLibraryPage(7, page)
        val sized = RoomCatalogCacheRepository(db = db, snapshotProvider = { scope }, now = { 1000L },
            artwork = ArtworkSizing({ _, _ -> ArtworkWidths(poster = 300, backdrop = 1344, still = 360) }, null))
        assertEquals(detail, sized.getCachedItemDetail("c1"))
        assertEquals(episodes, sized.getCachedEpisodes("s1", 1, ArtworkSurface.EPISODES))
        assertEquals(page, sized.getCachedDefaultLibraryPage(7))
        // Caching the item again retires the old row.
        val after = detail.copy(title = "After")
        sized.cacheItemDetail("c1", after)
        assertEquals(after, sized.getCachedItemDetail("c1"))
        assertNull(repo.getCachedItemDetail("c1"))
    }

    @Test
    fun retiringALegacyRowLeavesAnotherProfilesRow() = runTest {
        val first = scope!!
        val second = AuthScopeSnapshot("s1", "p2", "https://s1.example", null)
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "movie", title = "Before")
        repo.cacheItemDetail("c1", detail)
        scope = second
        repo.cacheItemDetail("c1", detail)
        // The write goes to the first profile; the profile switches while Room stores it.
        var reads = 0
        val sized = RoomCatalogCacheRepository(db = db, snapshotProvider = { if (reads++ == 0) first else second },
            now = { 1000L }, artwork = ArtworkSizing({ _, _ -> ArtworkWidths(backdrop = 1344) }, null))
        sized.cacheItemDetail("c1", detail.copy(title = "After"))
        assertEquals(detail, repo.getCachedItemDetail("c1"))
        scope = first
        assertNull(repo.getCachedItemDetail("c1"))
    }

    @Test
    fun roundTripsLibraries() = runTest {
        assertNull(repo.getCachedLibraries())
        repo.cacheLibraries(listOf(UserLibrary(id = 1, name = "Movies", type = "movie")))
        val back = repo.getCachedLibraries()
        assertEquals(1, back?.size)
        assertEquals("Movies", back?.first()?.name)
    }

    @Test
    fun roundTripsDefaultLibraryPagePerLibraryId() = runTest {
        repo.cacheDefaultLibraryPage(7, CatalogResponse(total = 1, items = listOf(BrowseItem(contentId = "c1", type = "movie", title = "A"))))
        assertEquals("c1", repo.getCachedDefaultLibraryPage(7)?.items?.first()?.contentId)
        // Distinct key per library id.
        assertNull(repo.getCachedDefaultLibraryPage(8))
    }

    @Test
    fun keepsALibrarysGroupedAndUngroupedDefaultPagesApart() = runTest {
        val grouped = CatalogResponse(total = 1, items = listOf(BrowseItem(contentId = "box-set", type = "box_set", title = "Saga")))
        repo.cacheDefaultLibraryPage(7, grouped, CatalogCacheWriteLease(0L), group = "box_set")
        // Box sets turned off: the grouped page must not answer the plain browse.
        assertNull(repo.getCachedDefaultLibraryPage(7))
        assertEquals(grouped, repo.getCachedDefaultLibraryPage(7, group = "box_set"))

        val plain = CatalogResponse(total = 1, items = listOf(BrowseItem(contentId = "c1", type = "movie", title = "A")))
        repo.cacheDefaultLibraryPage(7, plain)
        assertEquals(plain, repo.getCachedDefaultLibraryPage(7))
        assertEquals(grouped, repo.getCachedDefaultLibraryPage(7, group = "box_set"))
    }

    @Test
    fun roundTripsLibrarySectionsPerLibraryId() = runTest {
        val section = org.siloserver.silo.model.section.ResolvedSection(
            id = "recently_added",
            sectionType = "recently_added",
            title = "Recently Added",
            items = listOf(org.siloserver.silo.model.section.SectionItem(contentId = "c1", type = "movie", title = "A")),
        )
        repo.cacheLibrarySections(7, listOf(section))
        assertEquals("recently_added", repo.getCachedLibrarySections(7)?.first()?.id)
        assertNull(repo.getCachedLibrarySections(8))
        // Distinct from the browse-grid key for the same library id.
        assertNull(repo.getCachedDefaultLibraryPage(7))
    }

    @Test
    fun cacheIsScopedAndNoOpWithoutScope() = runTest {
        repo.cacheLibraries(listOf(UserLibrary(id = 1, name = "Movies", type = "movie")))
        scope = AuthScopeSnapshot("s2", "p1", "https://s2.example", null)
        assertNull(repo.getCachedLibraries())
        scope = null
        assertNull(repo.getCachedLibraries())
    }

    @Test
    fun writeStartedBeforeProfileSwitchIsNotAttributedToNewProfile() = runTest {
        val snapshotRequested = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val delayedRepo = RoomCatalogCacheRepository(
            db = db,
            snapshotProvider = {
                snapshotRequested.complete(Unit)
                releaseSnapshot.await()
                scope
            },
            identityTransitions = identityTransitions,
            now = { 1000L },
        )

        val oldProfileWrite = async {
            delayedRepo.cacheLibraries(listOf(UserLibrary(id = 1, name = "Profile A", type = "movie")))
        }
        snapshotRequested.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            scope = AuthScopeSnapshot("s1", "p2", "https://s1.example", null)
        }
        releaseSnapshot.complete(Unit)
        oldProfileWrite.await()

        assertNull(delayedRepo.getCachedLibraries())
    }

    @Test
    fun writeRequestedByOldProfileButInvokedAfterSwitchIsNotAttributedToNewProfile() = runTest {
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val oldProfileGeneration = identityTransitions.generation.value
        val guardedRepo = RoomCatalogCacheRepository(
            db = db,
            snapshotProvider = { scope },
            identityTransitions = identityTransitions,
            now = { 1000L },
        )
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            scope = AuthScopeSnapshot("s1", "p2", "https://s1.example", null)
        }

        guardedRepo.cacheLibraries(
            listOf(UserLibrary(id = 1, name = "Profile A", type = "movie")),
            CatalogCacheWriteLease(oldProfileGeneration),
        )

        assertEquals(0L, oldProfileGeneration)
        assertNull(guardedRepo.getCachedLibraries())
    }
    @Test
    fun languageInvalidationPurgesOnlyCurrentScopesDetailMetadata() = runTest {
        val owner = scope!!
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "series", title = "Old language")
        val seasons = org.siloserver.silo.model.catalog.SeasonsResponse()
        val episodes = org.siloserver.silo.model.catalog.EpisodesResponse()
        suspend fun populate() {
            repo.cacheItemDetail("c1", detail)
            repo.cacheSeasons("c1", seasons)
            repo.cacheEpisodes("c1", 1, episodes)
            repo.cacheLibraries(listOf(UserLibrary(id = 1, name = "Shows", type = "series")))
        }
        populate()
        scope = owner.copy(profileId = "p2")
        populate()
        scope = owner
        repo.invalidateDetailMetadata()
        assertNull(repo.getCachedItemDetail("c1"))
        assertNull(repo.getCachedSeasons("c1"))
        assertNull(repo.getCachedEpisodes("c1", 1))
        assertEquals("Shows", repo.getCachedLibraries()?.single()?.name)
        scope = owner.copy(profileId = "p2")
        assertEquals(detail, repo.getCachedItemDetail("c1"))
        assertEquals(seasons, repo.getCachedSeasons("c1"))
        assertEquals(episodes, repo.getCachedEpisodes("c1", 1))
    }

    @Test
    fun languagePurgeAfterProfileSwitchDeletesTheWriterAndRetainsTheActiveProfilesMetadata() = runTest {
        val owner = scope!!
        val detail = org.siloserver.silo.model.catalog.ItemDetail(contentId = "c1", type = "series", title = "Cached")
        val seasons = org.siloserver.silo.model.catalog.SeasonsResponse()
        val episodes = org.siloserver.silo.model.catalog.EpisodesResponse()
        suspend fun populate() {
            repo.cacheItemDetail("c1", detail)
            repo.cacheSeasons("c1", seasons)
            repo.cacheEpisodes("c1", 1, episodes)
        }
        populate()
        val language = org.siloserver.silo.domain.settings.CatalogLanguageRevision()
        language.changed(org.siloserver.silo.repository.port.CatalogCacheScope(owner.serverId, owner.profileId!!))
        scope = owner.copy(profileId = "p2")
        populate()
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("No live request expected") })
        val catalog = org.siloserver.silo.repository.CatalogRepository(
            org.siloserver.silo.network.api.CatalogApi(client), repo, catalogLanguage = language,
        )
        catalog.invalidateLocalizedMetadata()
        assertEquals(detail, repo.getCachedItemDetail("c1"))
        assertEquals(seasons, repo.getCachedSeasons("c1"))
        assertEquals(episodes, repo.getCachedEpisodes("c1", 1))
        scope = owner
        assertNull(catalog.getCachedItemDetail("c1"))
        assertNull(catalog.getCachedSeasons("c1"))
        assertNull(catalog.getCachedEpisodes("c1", 1))
    }

}
