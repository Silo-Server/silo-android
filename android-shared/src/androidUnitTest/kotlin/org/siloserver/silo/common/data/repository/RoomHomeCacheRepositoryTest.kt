package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.repository.port.HomeCacheWriteLease
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
class RoomHomeCacheRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var scope: AuthScopeSnapshot? = AuthScopeSnapshot("s1", "p1", "https://s1.example", null)

    private val repo = RoomHomeCacheRepository(
        db = db,
        snapshotProvider = { scope },
        now = { 1000L },
    )

    @AfterTest
    fun tearDown() = db.close()

    private fun section(id: String, item: String) = ResolvedSection(
        id = id,
        sectionType = "continue_watching",
        title = "Continue Watching",
        items = listOf(SectionItem(contentId = item, type = "movie", title = "Movie $item")),
    )

    @Test
    fun roundTripsCachedSections() = runTest {
        assertNull(repo.getCachedHome())
        repo.cacheHome(listOf(section("s-cw", "c1"), section("s-rec", "c2")))
        val back = repo.getCachedHome()
        assertEquals(2, back?.sections?.size)
        assertEquals("s-cw", back?.sections?.get(0)?.id)
        assertEquals("c1", back?.sections?.get(0)?.items?.first()?.contentId)
        assertEquals(1000L, back?.cachedAtMs)
    }

    @Test
    fun oversizedHomeIsNotCachedAndDropsPriorRow() = runTest {
        // A good small cache exists first.
        repo.cacheHome(listOf(section("s-cw", "c1")))
        assertEquals(1, repo.getCachedHome()?.sections?.size)

        // An oversized layout (> ~1.5MB serialized) must NOT be stored — a row
        // that big overflows SQLite's ~2MB CursorWindow and throws
        // SQLiteBlobTooBigException on the next read. It should be skipped and
        // the prior row dropped, so getCachedHome cleanly returns null.
        val bigTitle = "x".repeat(2_000)
        val huge = listOf(
            ResolvedSection(
                id = "big",
                sectionType = "row",
                title = "Big",
                items = (0 until 1_000).map {
                    SectionItem(contentId = "c$it", type = "movie", title = bigTitle)
                },
            ),
        )
        repo.cacheHome(huge)
        assertNull(repo.getCachedHome())
    }

    @Test
    fun cacheIsScopedAndNoOpWithoutScope() = runTest {
        repo.cacheHome(listOf(section("s-cw", "c1")))
        // Different scope → no cached home.
        scope = AuthScopeSnapshot("s2", "p1", "https://s2.example", null)
        assertNull(repo.getCachedHome())
        // No scope → no-op read.
        scope = null
        assertNull(repo.getCachedHome())
    }

    @Test
    fun writeRequestedByOldProfileButInvokedAfterSwitchIsNotAttributedToNewProfile() = runTest {
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val oldProfileGeneration = identityTransitions.generation.value
        val guardedRepo = RoomHomeCacheRepository(
            db = db,
            snapshotProvider = { scope },
            identityTransitions = identityTransitions,
            now = { 1000L },
        )
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            scope = AuthScopeSnapshot("s1", "p2", "https://s1.example", null)
        }

        guardedRepo.cacheHome(
            listOf(section("old", "c1")),
            HomeCacheWriteLease(oldProfileGeneration),
        )

        assertEquals(0L, oldProfileGeneration)
        assertNull(guardedRepo.getCachedHome())
    }

    @Test
    fun profileSwitchDuringHomeScopeResolutionDoesNotAttributeOldWriteToNewProfile() = runTest {
        val snapshotRequested = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val guardedRepo = RoomHomeCacheRepository(
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
            guardedRepo.cacheHome(listOf(section("old", "c1")))
        }
        snapshotRequested.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            scope = AuthScopeSnapshot("s1", "p2", "https://s1.example", null)
        }
        releaseSnapshot.complete(Unit)
        oldProfileWrite.await()

        assertNull(guardedRepo.getCachedHome())
    }
}
