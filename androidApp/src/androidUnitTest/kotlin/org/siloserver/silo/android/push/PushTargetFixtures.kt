package org.siloserver.silo.android.push

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.siloserver.silo.model.notifications.PushDeviceRegisterRequest
import org.siloserver.silo.model.notifications.PushDeviceRegisterResponse
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.ServerRegistry

/** A saved server's acknowledged registration as [profileId], as the registrar stores it. */
internal fun registeredInstallation(entry: ServerEntry, profileId: String, receiptServerId: String?) = PushInstallation(
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

/**
 * Saved servers as [PushTargetResolver] sees them: their entries, the login
 * held for each, this device's registrations with them, and what an address
 * without a recorded identity answers when asked.
 */
internal class SavedServers(vararg saved: ServerEntry) {
    val entries = MutableStateFlow(saved.toList())
    val logins = saved.associate { it.id to "login-${it.id}" }.toMutableMap()
    var installations = emptyList<PushInstallation>()
    val answeredIdentity = mutableMapOf<String, String>()

    /** The candidates each identity lookup was asked about, in order. */
    val probed = mutableListOf<List<String>>()

    fun resolver() = PushTargetResolver(
        installations = { installations },
        authorities = object : DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = null
            override suspend fun savedLoginAuthority(serverId: String, profileId: String, profileToken: String?) =
                logins[serverId]?.let { login ->
                    val url = entries.value.first { it.id == serverId }.url
                    DurableLoginAuthority(login, AuthScopeSnapshot.savedLogin(serverId, url, profileId, profileToken, 7))
                }
        },
        registry = Proxy.newProxyInstance(ServerRegistry::class.java.classLoader, arrayOf(ServerRegistry::class.java)) { _, method, _ ->
            check(method.name == "getEntries") { "Unexpected ${method.name}" }
            entries
        } as ServerRegistry,
        entriesWithIdentity = { serverId, candidates ->
            probed += candidates.map { it.id }
            candidates.filter { answeredIdentity[it.url] == serverId }
        },
    )
}
