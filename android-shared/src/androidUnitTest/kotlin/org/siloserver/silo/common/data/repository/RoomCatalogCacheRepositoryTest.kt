package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
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
}
