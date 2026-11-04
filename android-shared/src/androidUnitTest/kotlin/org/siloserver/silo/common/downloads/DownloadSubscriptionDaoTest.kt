package org.siloserver.silo.common.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DownloadSubscriptionEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadSubscriptionDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = db.downloadSubscriptionDao()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    @Test
    fun upsertAndQueryActiveSubscriptionsByScope() = runTest {
        dao.upsert(subscription(id = "s1", targetId = "series-1", enabled = true))
        dao.upsert(subscription(id = "s2", targetId = "series-2", enabled = false))
        dao.upsert(subscription(id = "other", serverId = "server-b", targetId = "series-3", enabled = true))

        val active = dao.getActive("server-a", "account-a", "profile-a")

        assertEquals(listOf("s1"), active.map { it.id })
        assertEquals("series-1", active.single().targetId)
    }

    @Test
    fun disableOnlyTouchesScopedRow() = runTest {
        dao.upsert(subscription(id = "s1", enabled = true))
        dao.disable("server-a", "account-a", "profile-a", "s1", updatedAt = 20L)

        val row = dao.get("server-a", "account-a", "profile-a", "s1")

        assertEquals(false, row?.enabled)
        assertEquals(20L, row?.updatedAt)
    }

    @Test
    fun deleteAllForServerRemovesOnlyThatServer() = runTest {
        dao.upsert(subscription(id = "s1", serverId = "server-a"))
        dao.upsert(subscription(id = "s2", serverId = "server-b"))
        dao.deleteAllForServer("server-a")

        assertNull(dao.get("server-a", "account-a", "profile-a", "s1"))
        assertTrue(dao.getActive("server-b", "account-a", "profile-a").isNotEmpty())
    }

    private fun subscription(
        id: String,
        serverId: String = "server-a",
        profileId: String = "profile-a",
        targetId: String = "series-1",
        enabled: Boolean = true,
    ) = DownloadSubscriptionEntity(
        id = id,
        serverId = serverId,
        accountId = "account-a",
        profileId = profileId,
        targetType = "series",
        targetId = targetId,
        displayTitle = "Series",
        mediaKind = "video",
        quality = "original",
        wifiOnly = true,
        enabled = enabled,
        includeExisting = false,
        keepUnwatchedLimit = 3,
        deleteWatchedAfterDays = 7,
        createdAt = 1L,
        updatedAt = 1L,
        lastEvaluatedAt = null,
        lastError = null,
    )
}
