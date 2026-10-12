package org.siloserver.silo.android.push

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.notifications.PushDeviceRegisterRequest
import org.siloserver.silo.model.notifications.PushDeviceRegisterResponse
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.ServerRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A push names the server deployment and profile it was sent for; the device
 * reads it with the saved login registered there, whichever is active, and
 * reads nothing for a server or profile it holds no registration and login for.
 */
class PushTargetResolverTest {
    private val home = ServerEntry(id = "home", url = "https://home.example")
    private val cabin = ServerEntry(id = "cabin", url = "https://cabin.example", verifiedServerId = "deploy-cabin")
    private val entries = MutableStateFlow(listOf(home, cabin))
    private val logins = mutableMapOf("home" to "login-home", "cabin" to "login-cabin")
    private val answeredIdentity = mutableMapOf<String, String>()
    private val probed = mutableListOf<List<String>>()

    private var installations = listOf(
        registered(home, profileId = "kids", receiptServerId = "deploy-home"),
        registered(cabin, profileId = "adult", receiptServerId = null),
    )

    private val resolver = PushTargetResolver(
        installations = { installations },
        authorities = object : DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = null
            override suspend fun savedLoginAuthority(serverId: String, profileId: String, profileToken: String?) =
                logins[serverId]?.let { login ->
                    val url = entries.value.first { it.id == serverId }.url
                    DurableLoginAuthority(login, AuthScopeSnapshot.savedLogin(serverId, url, profileId, profileToken, 7))
                }
        },
        registry = registry(),
        entriesWithIdentity = { serverId, candidates ->
            probed += candidates.map { it.id }
            candidates.filter { answeredIdentity[it.url] == serverId }
        },
    )

    @Test
    fun `a push is read with the saved login of the server and profile it names`() = runTest {
        val fromHome = resolver.resolve(PushIssuer("deploy-home", "kids"))!!
        assertEquals("home", fromHome.serverEntryId)
        assertEquals("kids", fromHome.profileId)
        assertEquals("login-home", fromHome.owner.loginId)
        assertEquals("kids-proof", fromHome.owner.scope.profileToken)
        assertEquals(home.url, fromHome.owner.scope.serverUrl)
        assertTrue(fromHome.owner.scope.isGenerationPinned, "never compared with the active scope")

        // An older server's receipt has no identity; the one recorded on its entry stands in.
        assertEquals("cabin", resolver.resolve(PushIssuer("deploy-cabin", "adult"))?.serverEntryId)
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `a registration without a recorded identity is matched by what its saved address answers`() = runTest {
        entries.value = listOf(home, cabin.copy(verifiedServerId = null))
        answeredIdentity[cabin.url] = "deploy-cabin"

        assertEquals("cabin", resolver.resolve(PushIssuer("deploy-cabin", "adult"))?.serverEntryId)
        assertEquals(listOf(listOf("cabin")), probed, "only the registration for that profile with no identity")
    }

    @Test
    fun `a server and profile more than one saved server claims is no target`() = runTest {
        // Server identities are self-reported, so a server signed into here can claim home's.
        val impostor = ServerEntry(id = "impostor", url = "https://impostor.example")
        entries.value = listOf(home, cabin, impostor)
        installations = installations + registered(impostor, profileId = "kids", receiptServerId = "deploy-home")

        assertNull(resolver.resolve(PushIssuer("deploy-home", "kids")))

        // Home had not reported its identity yet: its saved address still counts as a claim.
        installations = installations.map { if (it.intent.serverId == "home") it.copy(receipt = it.receipt?.copy(serverId = null)) else it }
        answeredIdentity[home.url] = "deploy-home"
        assertNull(resolver.resolve(PushIssuer("deploy-home", "kids")))
        assertEquals(listOf(listOf("home")), probed)
    }

    @Test
    fun `no server or profile without a live registration and login is a target`() = runTest {
        assertNull(resolver.resolve(PushIssuer("deploy-elsewhere", "kids")), "a server never registered with")
        assertNull(resolver.resolve(PushIssuer("deploy-home", "adult")), "home pushes for kids, not adult")

        logins["home"] = "login-next"
        assertNull(resolver.resolve(PushIssuer("deploy-home", "kids")), "another account signed in there since")
        logins.remove("home")
        assertNull(resolver.resolve(PushIssuer("deploy-home", "kids")), "signed out there")

        logins["home"] = "login-home"
        installations = listOf(registered(home, "kids", "deploy-home").let {
            it.copy(intent = it.intent.copy(registration = null), receipt = null)
        })
        assertNull(resolver.resolve(PushIssuer("deploy-home", "kids")), "unregistered there")
    }

    private fun registered(entry: ServerEntry, profileId: String, receiptServerId: String?) = PushInstallation(
        serverUrl = entry.url,
        deviceId = "device",
        key = "k".repeat(43),
        generation = 1,
        intent = PushInstallationIntent(
            serverId = entry.id,
            loginId = "login-${entry.id}",
            profileId = profileId,
            profileToken = "$profileId-proof",
            registration = PushDeviceRegisterRequest("android", "t".repeat(80), "device"),
        ),
        acknowledged = true,
        receipt = PushDeviceRegisterResponse("r1", "private_push", "1", "d1", receiptServerId),
    )

    private fun registry(): ServerRegistry =
        Proxy.newProxyInstance(ServerRegistry::class.java.classLoader, arrayOf(ServerRegistry::class.java)) { _, method, _ ->
            check(method.name == "getEntries") { "Unexpected ${method.name}" }
            entries
        } as ServerRegistry
}
