package org.siloserver.silo.android.push

import android.app.Application
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.notifications.PushDeviceRegisterRequest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28])
class PushInstallationStoreTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun entry()=PushInstallation("https://example.invalid","device","k".repeat(43),9007199254740993,
        PushInstallationIntent("server","login","profile","pin",PushDeviceRegisterRequest("android","t".repeat(80),"device")))
    @Test fun roundTripKeepsExactProofCounterIntentAndExclusiveOwner()=runTest {
        val root=tmp.newFolder(); val store=FilePushInstallationStore(root)
        assertTrue(store.read().isEmpty())
        store.write(listOf(entry()))
        assertEquals(listOf(entry()),store.read())
        assertTrue(File(root,"initialized").exists())
        assertFailsWith<Exception> { FilePushInstallationStore(root).read() }
        assertEquals(listOf(entry()),store.read())
    }
    @Test fun corruptedOrMissingProofFailsClosedWithoutReplacement()=runTest {
        val root=tmp.newFolder(); val store=FilePushInstallationStore(root)
        store.write(listOf(entry()))
        val file=File(root,"installations.json")
        file.writeText("corrupt")
        assertFailsWith<Exception>{store.read()}
        file.delete()
        assertFailsWith<Exception>{store.read()}
        assertFalse(file.exists())
    }
}
