package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.network.AndroidServerRegistry

/**
 * The same-profile fast path skips the server's profile exchange, so every
 * condition must hold; failing any one sends the offer down the full handoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemotePlaybackOwnIdentityPolicyTest {

    @Test fun acceptsTheTvsOwnProfileOnItsOwnServer() {
        assertTrue(accepts())
    }

    @Test fun refusesEachMissingCondition() {
        assertFalse(accepts(authorizedAtHello = false), "a controller not authorized at hello")
        assertFalse(accepts(holdsTemporaryIdentity = true), "a phone's identity installed or ending")
        assertFalse(accepts(signedIn = false), "a TV that is signed out")
        assertFalse(accepts(ownServerId = OTHER_SERVER_ID), "a different server")
        assertFalse(accepts(ownServerId = null), "no active server")
        assertFalse(accepts(ownProfileId = "profile-2"), "a different profile")
        assertFalse(accepts(ownProfileId = null), "no active profile")
        assertFalse(accepts(ownProfileId = ""), "a blank profile")
    }

    private fun accepts(
        authorizedAtHello: Boolean = true,
        holdsTemporaryIdentity: Boolean = false,
        signedIn: Boolean = true,
        ownServerId: String? = SERVER_ID,
        ownProfileId: String? = "profile-1",
    ) = RemotePlaybackOwnIdentityPolicy.accepts(
        authorizedAtHello = authorizedAtHello,
        holdsTemporaryIdentity = holdsTemporaryIdentity,
        signedIn = signedIn,
        ownServerId = ownServerId,
        ownProfileId = ownProfileId,
        offerServerId = SERVER_ID,
        offerProfileId = "profile-1",
    )

    private companion object {
        val SERVER_ID = AndroidServerRegistry.idFor("https://media.example.test")
        val OTHER_SERVER_ID = AndroidServerRegistry.idFor("https://other.example.test")
    }
}
