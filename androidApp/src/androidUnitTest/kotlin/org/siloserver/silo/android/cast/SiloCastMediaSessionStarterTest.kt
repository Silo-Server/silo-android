package org.siloserver.silo.android.cast

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiloCastMediaSessionStarterTest {
    @Test
    fun `active or paused media starts the service only while the app is foregrounded`() {
        val withMedia = RemoteServiceState(hasMedia = true)

        assertEquals(
            RemoteMediaServiceAction.Start,
            resolveRemoteMediaServiceAction(withMedia, appForeground = true),
        )
        assertEquals(
            RemoteMediaServiceAction.None,
            resolveRemoteMediaServiceAction(withMedia, appForeground = false),
        )
    }

    @Test
    fun `cleared media stops the service even while app is backgrounded`() {
        val empty = RemoteServiceState(hasMedia = false)

        assertEquals(
            RemoteMediaServiceAction.Stop,
            resolveRemoteMediaServiceAction(empty, appForeground = false),
        )
        assertEquals(
            RemoteMediaServiceAction.Stop,
            resolveRemoteMediaServiceAction(empty, appForeground = true),
        )
    }

    @Test
    fun starterNeverArmsTheStartForegroundWatchdog() {
        val starterSource = source("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastMediaSessionStarter.kt")

        assertFalse(
            starterSource.contains("startForegroundService("),
            "startForegroundService() arms the 5–10s watchdog before Media3 can call startForeground()",
        )
        assertTrue(
            starterSource.contains("appContext.startService(intent)"),
            "the starter must use a plain startService() and let Media3 promote to foreground",
        )
    }

    @Test
    fun serviceRegistersTheSessionSoMedia3CanEnterForeground() {
        val serviceSource = source("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastMediaSessionService.kt")

        assertTrue(
            serviceSource.contains("addSession(session)"),
            "without addSession, Media3 never shows a notification or calls startForeground()",
        )
    }

    private fun source(relativePath: String): String {
        val userDir = System.getProperty("user.dir")
        val candidates = listOfNotNull(
            File(relativePath),
            userDir?.let { File(it, relativePath) },
            userDir?.let { File(it, "androidApp/$relativePath") },
        )
        return candidates.first { it.isFile }.readText()
    }
}
