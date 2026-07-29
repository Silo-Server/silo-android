package org.siloserver.silo.common.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DownloadEntity
import org.siloserver.silo.common.data.sync.OutboxOperation
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.audiobook.AudiobookBookmarksStore
import org.siloserver.silo.common.ebook.EbookLocalStateStore
import org.siloserver.silo.common.store.ServerScopedJsonStatePurger
import org.siloserver.silo.model.audiobook.AudiobookBookmark
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * Removing a server wiped four token keys and its registry entry and nothing
 * else. Every Room row survived, so reclaiming space reclaimed none — and
 * because `idFor()` is base64 of the normalised URL, re-adding the same server
 * resurrected its Downloads list, resume positions, cached rows and pending
 * outbox ops under an identity the user believed they had deleted.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OrphanedServerDataPurgerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, SiloDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @AfterTest
    fun tearDown() = db.close()

    private fun registry(name: String): AndroidServerRegistry {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return AndroidServerRegistry(prefs)
    }

    private suspend fun seed(serverId: String, mediaFileId: Int, recordId: String) {
        db.downloadDao().upsert(
            DownloadEntity(
                serverId = serverId,
                profileId = "p1",
                mediaFileId = mediaFileId,
                recordId = recordId,
                contentId = "c$mediaFileId",
                title = "Episode $mediaFileId",
                subtitle = null,
                posterUrl = null,
                posterThumbhash = null,
                year = null,
                seriesTitle = null,
                seasonNumber = null,
                episodeNumber = null,
                fileName = "file.mkv",
                container = "mkv",
                localUri = null,
                mediaType = "movie",
                overview = null,
                author = null,
                narrator = null,
                durationSeconds = null,
                chaptersJson = null,
                status = "complete",
                kind = "queued",
                fileSize = 0L,
                bytesSent = 0L,
                createdAt = "2026-06-16T00:00:00Z",
                completedAt = null,
                updatedAtMs = 1L,
            ),
        )
        db.dirtyOperationDao().insert(
            DirtyOperationEntity(
                opKind = OutboxOperation.SET_WATCHED,
                serverId = serverId,
                profileId = "p1",
                targetContentId = "c$mediaFileId",
                targetFileId = null,
                coalesceKey = "$serverId|p1|c$mediaFileId|${OutboxOperation.SET_WATCHED}",
                idempotencyKey = "idem-$mediaFileId",
                payloadJson = "{}",
                createdAtMs = 0L,
                nextAttemptAtMs = 0L,
            ),
        )
    }

    private fun purger(
        registry: AndroidServerRegistry,
        cancelled: MutableList<String> = mutableListOf(),
        activeFileId: Int? = null,
    ) = OrphanedServerDataPurger(
        registry = registry,
        purgeDao = db.serverPurgeDao(),
        storage = DownloadStorage(context),
        cancelDownload = { cancelled += it },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        io = UnconfinedTestDispatcher(),
        activePlaybackFileId = { activeFileId },
    )

    @Test
    fun `rows for a server that is no longer registered are purged`() = runTest {
        val registry = registry("purge-basic")
        val keptId = registry.addOrUpdate("https://keep.example")
        seed(keptId, mediaFileId = 1, recordId = "rec-keep")
        seed("orphan-server", mediaFileId = 2, recordId = "rec-orphan")

        val cancelled = mutableListOf<String>()
        val purged = purger(registry, cancelled).purgeOnce()

        assertEquals(setOf("orphan-server"), purged)
        assertEquals(
            listOf("rec-orphan"),
            cancelled,
            "a live DownloadWorker rewrites its row on every progress tick",
        )
        assertNull(db.downloadDao().get("orphan-server", "p1", 2))
        assertTrue(db.dirtyOperationDao().dueBatch("orphan-server", "p1", 1L, 10).isEmpty())

        // The registered server is untouched.
        assertEquals("rec-keep", db.downloadDao().get(keptId, "p1", 1)?.recordId)
        assertEquals(1, db.dirtyOperationDao().dueBatch(keptId, "p1", 1L, 10).size)
    }

    @Test
    fun `an unreadable registry blob purges nothing`() = runTest {
        // The registry decodes its whole state from one JSON blob and treats a
        // decode failure as "no servers". Purging on that reading would turn a
        // corrupt preference — recoverable by signing in again, because ids are
        // derived from the URL — into permanent deletion of every download,
        // resume position and queued outbox op on the device.
        val prefs = context.getSharedPreferences("purge-corrupt", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putString(AndroidServerRegistry.KEY_REGISTRY_STATE, "{ not valid json")
            .commit()
        val registry = AndroidServerRegistry(prefs)
        assertTrue(registry.entries.value.isEmpty(), "corrupt state presents as an empty registry")
        assertTrue(registry.stateLoadFailed, "and must be flagged as untrustworthy")
        seed("server-with-data", mediaFileId = 7, recordId = "rec-precious")

        val cancelled = mutableListOf<String>()
        val purged = purger(registry, cancelled).purgeOnce()

        assertEquals(emptySet(), purged)
        assertEquals(emptyList(), cancelled)
        assertEquals(
            "rec-precious",
            db.downloadDao().get("server-with-data", "p1", 7)?.recordId,
            "downloads must survive a registry that could not be read",
        )
        assertEquals(1, db.dirtyOperationDao().dueBatch("server-with-data", "p1", 1L, 10).size)
    }

    @Test
    fun `a file that is currently playing defers its whole server`() = runTest {
        val registry = registry("purge-active")
        seed("orphan-server", mediaFileId = 42, recordId = "rec-playing")

        // Offline PiP survives leaving the screen and outlives a sign-out.
        val purged = purger(registry, activeFileId = 42).purgeOnce()

        assertEquals(emptySet(), purged)
        assertEquals("rec-playing", db.downloadDao().get("orphan-server", "p1", 42)?.recordId)

        // Once playback ends the next pass collects it — nothing is stranded.
        assertEquals(setOf("orphan-server"), purger(registry).purgeOnce())
        assertNull(db.downloadDao().get("orphan-server", "p1", 42))
    }

    @Test
    fun `purging is idempotent and re-runnable`() = runTest {
        val registry = registry("purge-idempotent")
        seed("orphan-server", mediaFileId = 7, recordId = "rec-7")

        assertEquals(setOf("orphan-server"), purger(registry).purgeOnce())
        assertEquals(
            emptySet(),
            purger(registry).purgeOnce(),
            "a second pass must find nothing — this runs on every cold start",
        )
    }

    @Test
    fun `room rows remain retry evidence until worker cancellation is acknowledged`() = runTest {
        val registry = registry("purge-await-cancel")
        seed("orphan-server", mediaFileId = 8, recordId = "rec-8")
        val cancellationStarted = CompletableDeferred<Unit>()
        val cancellationAcknowledged = CompletableDeferred<Unit>()
        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {
                cancellationStarted.complete(Unit)
                cancellationAcknowledged.await()
            },
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
            activePlaybackFileId = { null },
        )

        val purge = backgroundScope.async { purger.purgeOnce() }
        cancellationStarted.await()
        assertNotNull(db.downloadDao().get("orphan-server", "p1", 8))

        cancellationAcknowledged.complete(Unit)
        assertEquals(setOf("orphan-server"), purge.await())
        assertNull(db.downloadDao().get("orphan-server", "p1", 8))
    }

    @Test
    fun `failed worker cancellation retains rows and a later pass retries`() = runTest {
        val registry = registry("purge-cancel-retry")
        seed("orphan-server", mediaFileId = 12, recordId = "rec-12")
        var cancellationFails = true
        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {
                if (cancellationFails) error("work manager unavailable")
            },
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
            activePlaybackFileId = { null },
        )

        assertEquals(emptySet(), purger.purgeOnce())
        assertNotNull(db.downloadDao().get("orphan-server", "p1", 12))

        cancellationFails = false
        assertEquals(setOf("orphan-server"), purger.purgeOnce())
        assertNull(db.downloadDao().get("orphan-server", "p1", 12))
    }

    @Test
    fun `failed file deletion retains rows and a later pass retries`() = runTest {
        val registry = registry("purge-storage-retry")
        seed("orphan-server", mediaFileId = 13, recordId = "rec-13")
        var deletionFails = true
        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {},
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
            activePlaybackFileId = { null },
            deleteDownloadStorage = {
                if (deletionFails) error("filesystem unavailable")
            },
        )

        assertEquals(emptySet(), purger.purgeOnce())
        assertNotNull(db.downloadDao().get("orphan-server", "p1", 13))

        deletionFails = false
        assertEquals(setOf("orphan-server"), purger.purgeOnce())
        assertNull(db.downloadDao().get("orphan-server", "p1", 13))
    }

    @Test
    fun `json-only orphan state is discovered while registered state is retained`() = runTest {
        val baseDir = Files.createTempDirectory("silo-json-orphans").toFile()
        val registry = registry("purge-json-only")
        val registeredId = registry.addOrUpdate("https://registered-json.example")
        val orphanId = "https://orphan.example/" + "long-segment-".repeat(20)
        val ebook = EbookLocalStateStore(baseDir)
        val audiobook = AudiobookBookmarksStore(baseDir)
        val snapshot = EbookLocalStateStore.ProgressSnapshot(
            fileId = 91,
            location = "chapter-1",
            progress = 0.25,
            updatedAtMs = 1L,
        )
        ebook.writeProgress(orphanId, "p1", "book", snapshot)
        ebook.writeProgress(registeredId, "p1", "book", snapshot)
        audiobook.add(
            orphanId,
            "p1",
            "audio",
            AudiobookBookmark(
                id = "bookmark-1",
                positionSeconds = 12.0,
                createdAtMs = 1L,
            ),
        )

        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {},
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
            activePlaybackFileId = { null },
            jsonStatePurger = ServerScopedJsonStatePurger(baseDir),
        )

        assertEquals(emptySet(), purger.purgeOnce(), "JSON-only state has no Room server id")
        assertNull(ebook.readProgress(orphanId, "p1", "book"))
        assertTrue(audiobook.list(orphanId, "p1", "audio").isEmpty())
        assertEquals(snapshot, ebook.readProgress(registeredId, "p1", "book"))
    }

    @Test
    fun `cancelling an in-flight purge cannot strand partially deleted server data`() = runTest {
        val registry = registry("purge-cancel")
        seed("orphan-server", mediaFileId = 9, recordId = "rec-9")
        val rowDeletionStarted = CompletableDeferred<Unit>()
        val allowRowDeletion = CompletableDeferred<Unit>()
        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {},
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
            activePlaybackFileId = { null },
            purgeRows = { serverId ->
                rowDeletionStarted.complete(Unit)
                allowRowDeletion.await()
                db.serverPurgeDao().deleteAllRowsForServer(serverId)
            },
        )

        val purge = backgroundScope.async { purger.purgeOnce() }
        rowDeletionStarted.await()
        purge.cancel()
        allowRowDeletion.complete(Unit)
        purge.join()

        assertTrue(purge.isCancelled)
        assertNull(
            db.downloadDao().get("orphan-server", "p1", 9),
            "once file deletion has begun, cancellation must not leave the Room half behind",
        )
    }

    @Test
    fun `removal during startup scan triggers a second purge`() = runTest {
        val serverId = "removed-during-start"
        val registry = MutableTestServerRegistry(serverId)
        seed(serverId, mediaFileId = 10, recordId = "rec-10")
        seed("preexisting-orphan", mediaFileId = 11, recordId = "rec-11")
        val initialPurgeStarted = CompletableDeferred<Unit>()
        val finishInitialPurge = CompletableDeferred<Unit>()
        val initialRowsPurged = CompletableDeferred<Unit>()
        val rowsPurged = CompletableDeferred<Unit>()
        val secondPurgeStarted = CompletableDeferred<Unit>()
        val allowSecondPurge = CompletableDeferred<Unit>()
        val purger = OrphanedServerDataPurger(
            registry = registry,
            purgeDao = db.serverPurgeDao(),
            storage = DownloadStorage(context),
            cancelDownload = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.Default,
            activePlaybackFileId = { null },
            purgeRows = { orphanId ->
                if (orphanId == "preexisting-orphan") {
                    initialPurgeStarted.complete(Unit)
                    finishInitialPurge.await()
                }
                if (orphanId == serverId) {
                    secondPurgeStarted.complete(Unit)
                    allowSecondPurge.await()
                }
                db.serverPurgeDao().deleteAllRowsForServer(orphanId)
                if (orphanId == "preexisting-orphan") {
                    initialRowsPurged.complete(Unit)
                }
                if (orphanId == serverId) {
                    rowsPurged.complete(Unit)
                }
            },
        )

        val observer = purger.start()
        var observerFailure: Throwable? = null
        observer.invokeOnCompletion { observerFailure = it }
        try {
            initialPurgeStarted.await()
            registry.remove(serverId)
            assertTrue(registry.entries.value.none { it.id == serverId })
            finishInitialPurge.complete(Unit)
            initialRowsPurged.await()
            secondPurgeStarted.await()
            assertTrue(observer.isActive, "purge observer stopped: $observerFailure")
            assertNull(db.downloadDao().get("preexisting-orphan", "p1", 11))
            assertTrue(
                db.downloadDao().get(serverId, "p1", 10) != null,
                "second-pass deletion must remain gated until the snapshot assertion completes",
            )

            allowSecondPurge.complete(Unit)
            rowsPurged.await()
            assertNull(db.downloadDao().get(serverId, "p1", 10))
        } finally {
            withContext(NonCancellable) {
                finishInitialPurge.complete(Unit)
                allowSecondPurge.complete(Unit)
                observer.cancel()
                observer.join()
            }
        }
    }
}

private class MutableTestServerRegistry(serverId: String) : ServerRegistry {
    private val mutableEntries = MutableStateFlow(
        listOf(ServerEntry(id = serverId, url = "https://removed-during-start.example")),
    )
    override val entries: StateFlow<List<ServerEntry>> = mutableEntries
    override val activeServerId: StateFlow<String?> = MutableStateFlow(serverId)
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(mutableEntries.value.single())

    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = error("not used")
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun remove(serverId: String) {
        mutableEntries.value = mutableEntries.value.filterNot { it.id == serverId }
    }
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) = Unit
    override suspend fun touchActive() = Unit
}
