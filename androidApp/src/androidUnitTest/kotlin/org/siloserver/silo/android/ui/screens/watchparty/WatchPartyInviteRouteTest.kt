package org.siloserver.silo.android.ui.screens.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.android.ui.navigation.Route

// Route strings call android.net.Uri.encode, which needs Robolectric's runtime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchPartyInviteRouteTest {

    @Test
    fun `an app link becomes a hub route that carries only an opaque id`() {
        val link = watchPartyAppLinkOrNull(
            "silo://watch-party?server=https%3A%2F%2Fmedia.example.com&token=secret-join-token",
        )!!
        val route = watchPartyInviteRoute(link)

        assertEquals("watch_party?invite=${watchPartyInviteHandoffId(link)}", route)
        assertFalse(route.contains("secret"))
        assertFalse(route.contains("media.example.com"))
    }

    @Test
    fun `hub and lobby routes`() {
        assertEquals("watch_party", Route.WatchPartyHub().route)
        assertEquals("watch_party?host=abc", Route.WatchPartyHub(host = "abc").route)
        assertEquals("watch_party/lobby/room-1", Route.WatchPartyLobby("room-1").route)
    }
}
