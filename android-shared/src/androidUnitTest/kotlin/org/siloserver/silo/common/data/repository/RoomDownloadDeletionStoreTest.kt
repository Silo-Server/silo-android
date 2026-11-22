package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RoomDownloadDeletionStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var clock = 1_000L
    private val store = RoomDownloadDeletionStore(db) { clock }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `enqueue then read back by scope and globally`() = runTest {
        store.enqueue("srv1", "acct", "profA", "dl-1", mediaFileId = 7)
        store.enqueue("srv1", "acct", "profA", "dl-2", mediaFileId = null)
        store.enqueue("srv2", "acct", "profB", "dl-3", mediaFileId = 9)

        assertEquals(setOf("dl-1", "dl-2", "dl-3"), store.allPendingRecordIds())

        val scoped = store.pendingForScope("srv1", "acct", "profA")
        assertEquals(setOf("dl-1", "dl-2"), scoped.map { it.recordId }.toSet())
        assertEquals(7, scoped.first { it.recordId == "dl-1" }.mediaFileId)
        assertEquals(null, scoped.first { it.recordId == "dl-2" }.mediaFileId)
    }

    @Test
    fun `remove drops only the targeted scoped tombstone`() = runTest {
        store.enqueue("srv1", "acct", "profA", "dl-1", 1)
        store.enqueue("srv2", "acct", "profB", "dl-1", 1) // same recordId, different scope

        store.remove("srv1", "acct", "profA", "dl-1")

        assertEquals(setOf("dl-1"), store.allPendingRecordIds())
        assertEquals(emptyList(), store.pendingForScope("srv1", "acct", "profA"))
        assertEquals(listOf("dl-1"), store.pendingForScope("srv2", "acct", "profB").map { it.recordId })
    }

    @Test
    fun `enqueue upserts on the scoped key`() = runTest {
        store.enqueue("srv1", "acct", "profA", "dl-1", mediaFileId = 7)
        store.enqueue("srv1", "acct", "profA", "dl-1", mediaFileId = 8)

        val scoped = store.pendingForScope("srv1", "acct", "profA")
        assertEquals(1, scoped.size)
        assertEquals(8, scoped.single().mediaFileId)
    }
}
