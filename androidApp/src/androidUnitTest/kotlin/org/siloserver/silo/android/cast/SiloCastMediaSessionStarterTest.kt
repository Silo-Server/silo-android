package org.siloserver.silo.android.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class SiloCastMediaSessionStarterTest {
    @Test
    fun `active playback starts a foreground service only while app is foregrounded`() {
        val active = RemoteServiceState(hasMedia = true, needsForegroundStart = true)

        assertEquals(
            RemoteMediaServiceAction.StartForeground,
            resolveRemoteMediaServiceAction(active, appForeground = true),
        )
        assertEquals(
            RemoteMediaServiceAction.None,
            resolveRemoteMediaServiceAction(active, appForeground = false),
        )
    }

    @Test
    fun `paused media uses an ordinary service start only while app is foregrounded`() {
        val paused = RemoteServiceState(hasMedia = true, needsForegroundStart = false)

        assertEquals(
            RemoteMediaServiceAction.Start,
            resolveRemoteMediaServiceAction(paused, appForeground = true),
        )
        assertEquals(
            RemoteMediaServiceAction.None,
            resolveRemoteMediaServiceAction(paused, appForeground = false),
        )
    }

    @Test
    fun `cleared media stops the service even while app is backgrounded`() {
        val empty = RemoteServiceState(hasMedia = false, needsForegroundStart = false)

        assertEquals(
            RemoteMediaServiceAction.Stop,
            resolveRemoteMediaServiceAction(empty, appForeground = false),
        )
    }
}
