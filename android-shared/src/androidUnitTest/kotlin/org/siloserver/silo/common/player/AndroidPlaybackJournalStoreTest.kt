package org.siloserver.silo.common.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.PlaybackStopV2
import org.siloserver.silo.repository.PlaybackJournalEntry
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class AndroidPlaybackJournalStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun context() = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = directory.root
    }
    private fun entry() = PlaybackJournalEntry("server", "https://example.invalid", "login", "account", "profile",
        "11111111-1111-4111-8111-111111111111", "attempt", JsonObject(emptyMap()), "session", stop = PlaybackStopV2(
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"))

    @Test fun durableBytesRetainExactStopAndSecondOwnerCannotReadOrWrite() = runTest {
        val store = AndroidPlaybackJournalStore(context())
        store.write(listOf(entry()))
        assertEquals(listOf(entry()), store.read())
        val persisted = File(directory.root, "playback-v2/requests.json").readText()
        assertEquals(listOf(entry()), SiloJson.decodeFromString<List<PlaybackJournalEntry>>(persisted))
        val secondOwner = AndroidPlaybackJournalStore(context())
        assertFails { secondOwner.read() }
        assertFails { secondOwner.write(emptyList()) }
        assertEquals(persisted, File(directory.root, "playback-v2/requests.json").readText())
    }

    @Test fun unreadableJournalFailsClosedInsteadOfReplacingRecoveryState() = runTest {
        val file = File(directory.root, "playback-v2/requests.json")
        file.parentFile!!.mkdirs()
        file.writeText("incomplete journal")
        assertFails { AndroidPlaybackJournalStore(context()).read() }
        assertEquals("incomplete journal", file.readText())
    }
}
