package com.continuum.app.android.ui.navigation

import com.continuum.app.common.downloads.DownloadStorage
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
        storage.prepareWrite("srv1", "profB", 1, fileName = "one.mkv").writeBytes(ByteArray(100))
        storage.prepareWrite("srv2", "profA", 2, fileName = "two.mkv").writeBytes(ByteArray(100))

        assertFalse(hasLocalDownloadsForScope(storage, "srv1", "profA"))

        storage.prepareWrite("srv1", "profA", 3, fileName = "three.mkv").writeBytes(ByteArray(100))

        assertTrue(hasLocalDownloadsForScope(storage, "srv1", "profA"))
    }

    @Test
    fun `download tab visibility uses records or active scoped local bytes`() {
        assertFalse(shouldShowDownloadsTab(serverRecordCount = 0, activeScopeLocalBytes = 0L))
        assertTrue(shouldShowDownloadsTab(serverRecordCount = 1, activeScopeLocalBytes = 0L))
        assertTrue(shouldShowDownloadsTab(serverRecordCount = 0, activeScopeLocalBytes = 1L))
    }

    @Test
    fun `missing active scope does not make global downloads visible`() {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        storage.prepareWrite("srv1", "profA", 1, fileName = "one.mkv").writeBytes(ByteArray(100))

        assertFalse(hasLocalDownloadsForScope(storage, null, "profA"))
        assertFalse(hasLocalDownloadsForScope(storage, "srv1", null))
    }

    private fun com.continuum.app.common.downloads.DownloadTarget.writeBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }
}
