package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.common.downloads.DownloadStorage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileDownloadVisibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `local download visibility only counts active server profile bytes`() {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        storage.prepareWrite("srv1", "acct", "profB", 1, fileName = "one.mkv").writeBytes(ByteArray(100))
        storage.prepareWrite("srv2", "acct", "profA", 2, fileName = "two.mkv").writeBytes(ByteArray(100))

        assertFalse(hasLocalDownloadsForScope(storage, "srv1", "acct", "profA"))

        storage.prepareWrite("srv1", "acct", "profA", 3, fileName = "three.mkv").writeBytes(ByteArray(100))

        assertTrue(hasLocalDownloadsForScope(storage, "srv1", "acct", "profA"))
    }

    @Test
    fun `download tab visibility uses records or active scoped local bytes`() {
        assertFalse(shouldShowDownloadsTab(serverRecordCount = 0, activeScopeLocalBytes = 0L))
        assertTrue(shouldShowDownloadsTab(serverRecordCount = 1, activeScopeLocalBytes = 0L))
        assertTrue(shouldShowDownloadsTab(serverRecordCount = 0, activeScopeLocalBytes = 1L))
    }

    @Test
    fun `startup routes to downloads when local media exists and server cannot be used`() {
        assertFalse(
            shouldStartOnDownloads(
                hasLocalDownloads = false,
                isDeviceOnline = false,
                canUseServer = false,
            ),
        )
        assertTrue(
            shouldStartOnDownloads(
                hasLocalDownloads = true,
                isDeviceOnline = false,
                canUseServer = true,
            ),
        )
        assertTrue(
            shouldStartOnDownloads(
                hasLocalDownloads = true,
                isDeviceOnline = true,
                canUseServer = false,
            ),
        )
        assertFalse(
            shouldStartOnDownloads(
                hasLocalDownloads = true,
                isDeviceOnline = true,
                canUseServer = true,
            ),
        )
    }

    @Test
    fun `missing active scope does not make global downloads visible`() {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        storage.prepareWrite("srv1", "acct", "profA", 1, fileName = "one.mkv").writeBytes(ByteArray(100))

        assertFalse(hasLocalDownloadsForScope(storage, null, "acct", "profA"))
        assertFalse(hasLocalDownloadsForScope(storage, "srv1", "acct", null))
    }

    private fun org.siloserver.silo.common.downloads.DownloadTarget.writeBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }
}
