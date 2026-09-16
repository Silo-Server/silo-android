package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.port.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class PersonalWriteJournalTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SiloDatabase::class.java)
        .allowMainThreadQueries().build()
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
    private var login: String? = "login-1"
    private val authority = object : DurableLoginAuthorityProvider {
        override suspend fun snapshotDurableLoginAuthority() = login?.let { DurableLoginAuthority(it, scope) }
    }
    private fun repo() = RoomUserItemStateRepository(db, { scope }, ebookAuthorities = authority)
    @AfterTest fun close() = db.close()

    @Test fun newWritesPersistExactWireAndAuthorityWithoutCredentialsAndSettleIndividually() = runTest {
        val repo = repo()
        for (command in listOf(PersonalWrite.Watched("item", true), PersonalWrite.Rating("item", 4), PersonalWrite.Rating("item", null))) {
            val handle = assertNotNull(repo.beginPersonalWrite(command))
            val row = assertNotNull(db.dirtyOperationDao().getById(handle.opId))
            assertEquals("personal_uncertain", row.state)
            assertTrue(row.payloadJson.contains("login-1"))
            assertTrue(row.payloadJson.contains(command.method))
            assertFalse(row.payloadJson.contains("proof"))
            assertEquals(scope, handle.scope)
            repo.completePersonalWrite(handle.copy(command = PersonalWrite.Rating("wrong", 1)))
            assertEquals(row, db.dirtyOperationDao().getById(handle.opId))
            repo.completePersonalWrite(handle)
            assertNull(db.dirtyOperationDao().getById(handle.opId))
        }
        assertEquals(true, db.contentItemStateDao().get("server", "profile", "item")?.watched)
        assertNull(db.contentItemStateDao().get("server", "profile", "item")?.ratingValue)
    }

    @Test fun unresolvedWriteIsSupersededByTheNextWriteAndNeverProjects() = runTest {
        val stale = assertNotNull(repo().beginPersonalWrite(PersonalWrite.Rating("item", 3)))
        val next = assertNotNull(repo().beginPersonalWrite(PersonalWrite.Rating("item", 4)))
        assertNull(db.dirtyOperationDao().getById(stale.opId))
        assertNotNull(db.dirtyOperationDao().getById(next.opId))
        repo().completePersonalWrite(stale) // late acknowledgement of a superseded attempt
        assertNull(db.contentItemStateDao().get("server", "profile", "item"))
        repo().abandonPersonalWrite(next)
        assertEquals(0, db.dirtyOperationDao().count())
        assertNull(db.contentItemStateDao().get("server", "profile", "item"))
    }

    @Test fun oldQueueBytesAndAuthorityAreNeverCoalescedOrResetByFreshWritesOrPositions() = runTest {
        val dao = db.dirtyOperationDao()
        val old = listOf("SET_POSITION", "SET_WATCHED", "SET_RATING").mapIndexed { index, kind ->
            val row = DirtyOperationEntity(opKind = kind, serverId = "server", profileId = "profile",
                targetContentId = "item", targetFileId = 7, coalesceKey = "server|profile|item|$kind",
                idempotencyKey = "old-$index", payloadJson = "  {old exact bytes}  ",
                state = if (index == 0) "in_flight" else "pending", createdAtMs = 1,
                attemptCount = 2, nextAttemptAtMs = 9000, membershipAuthority = "old authority")
            row.copy(id = dao.insert(row))
        }
        val repo = repo()
        assertNull(repo.beginPersonalWrite(PersonalWrite.Watched("item", true)))
        assertNull(repo.beginPersonalWrite(PersonalWrite.Rating("item", 4)))
        repo.recordPosition("item", 7, 42.0, 100.0)
        assertEquals(old, old.map { dao.getById(it.id) })
        assertEquals(3, dao.count())
        assertEquals(42.0, repo.localPosition("item", 7))
        assertNull(db.contentItemStateDao().get("server", "profile", "item"))
    }

    @Test fun acknowledgementCannotOverwriteNewerLocalProjectionOrResume() = runTest {
        val repo = repo()
        val handle = assertNotNull(repo.beginPersonalWrite(PersonalWrite.Watched("item", true)))
        val newer = org.siloserver.silo.common.data.db.entity.ContentItemStateEntity(
            "server", "profile", "item", false, 5, null, Long.MAX_VALUE, null)
        db.contentItemStateDao().upsert(newer)
        repo.recordPosition("item", 7, 75.0, 100.0)
        repo.completePersonalWrite(handle)
        assertEquals(newer, db.contentItemStateDao().get("server", "profile", "item"))
        assertEquals(75.0, repo.localPosition("item", 7))
        assertNull(db.dirtyOperationDao().getById(handle.opId))
    }

    @Test fun missingSavedAuthorityAndInvalidInputDoNotProjectOrJournal() = runTest {
        login = null
        assertNull(repo().beginPersonalWrite(PersonalWrite.Watched("item", true)))
        login = "login-1"
        assertNull(repo().beginPersonalWrite(PersonalWrite.Rating("item", 8)))
        scope = scope.copy(credentialGenerationId = "temporary")
        assertNull(repo().beginPersonalWrite(PersonalWrite.Watched("item", true)))
        assertEquals(0, db.dirtyOperationDao().count())
        assertNull(db.contentItemStateDao().get("server", "profile", "item"))
    }
}
