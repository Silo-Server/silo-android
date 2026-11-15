package org.siloserver.silo.common.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.network.AccountSignIn
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** How a pairing commit picks and refreshes saved accounts (contract §B.3, §B.7; design §5.3). */
@RunWith(RobolectricTestRunner::class)
class RegistryPairingAuthPortAccountsTest {
    private val serverUrl = "https://silo.example"

    private class Fixture(val registry: AndroidServerRegistry, val tokens: EncryptedTokenManagerImpl, val serverId: String, val samId: String)

    /** One server with sam (server user u-sam) signed in. */
    private suspend fun fixture(name: String): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        val serverId = registry.addOrUpdate(serverUrl)
        val samId = requireNotNull(
            tokens.replaceAccountSession(
                serverId = serverId,
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresIn = 3600,
                account = AccountSignIn(serverUserId = "u-sam", username = "sam"),
            ),
        )
        return Fixture(registry, tokens, serverId, samId)
    }

    private suspend fun commit(fixture: Fixture, user: User?, accountOf: (suspend (String, String) -> User?)? = null) =
        RegistryPairingAuthPort(fixture.tokens, fixture.registry, accountOf = accountOf).commitApprovedSession(
            serverUrl = serverUrl,
            serverName = "Home",
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresIn = 7200,
            expectedIdentity = null,
            verifiedServerId = null,
            user = user,
        )

    /** An older server's approval names no user: /me with the new token decides which account it refreshes. */
    @Test
    fun aPollWithoutUserReadsMeFirstSoASavedAccountIsRefreshedNotDuplicated() = runTest {
        val fixture = fixture("pairing-accounts-me")
        val asked = mutableListOf<Pair<String, String>>()

        val committed = commit(fixture, user = null) { url, token ->
            asked += url to token
            User(id = "u-sam", username = "sam", email = "", role = "user")
        }

        assertEquals(listOf(serverUrl to "new-access"), asked)
        assertEquals(PairingCommittedAccount(fixture.serverId, fixture.samId), committed)
        assertEquals(1, fixture.registry.accounts.value.size)
        assertEquals("new-access", fixture.tokens.getAccessToken())
    }

    /** Another user on the same server becomes a second account; the first stays. */
    @Test
    fun anotherUserOnTheSameServerIsItsOwnAccount() = runTest {
        val fixture = fixture("pairing-accounts-second")

        val committed = commit(fixture, User(id = "u-alex", username = "alex", email = "", role = "user"))

        assertNotEquals(fixture.samId, committed?.accountId)
        assertEquals(setOf("sam", "alex"), fixture.registry.accounts.value.map { it.username }.toSet())
    }

    /** A refreshed saved account keeps its remembered profile; a PIN profile's proof can't survive the new session. */
    @Test
    fun aRefreshedAccountKeepsItsRememberedProfileUnlessItHasAPin() = runTest {
        for (hasPin in listOf(false, true)) {
            val fixture = fixture("pairing-accounts-profile-$hasPin")
            fixture.registry.setCachedProfiles(fixture.samId, listOf(CachedProfile(id = "p-kid", name = "Kid", hasPin = hasPin)))
            fixture.tokens.setProfileIdentity("p-kid", "pin-proof")
            fixture.registry.setAccountProfileId(fixture.samId, "p-kid")

            commit(fixture, User(id = "u-sam", username = "sam", email = "", role = "user"))

            val sam = fixture.registry.accounts.value.single()
            if (hasPin) {
                assertNull(sam.profileId)
                assertNull(fixture.tokens.getProfileId())
            } else {
                assertEquals("p-kid", sam.profileId)
                assertEquals("p-kid", fixture.tokens.getProfileId())
            }
            // The proof was bound to the old login session either way.
            assertNull(fixture.tokens.getProfileToken())
        }
    }
}
