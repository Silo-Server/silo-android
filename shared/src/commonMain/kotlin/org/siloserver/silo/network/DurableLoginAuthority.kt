package org.siloserver.silo.network

/** A persisted login incarnation captured atomically with its live request scope. */
data class DurableLoginAuthority(val loginId: String, val scope: AuthScopeSnapshot) {
    override fun toString(): String = "DurableLoginAuthority(loginId=<redacted>, scope=$scope)"
}

/** Persistent implementations only; temporary credentials cannot supply durable authority. */
interface DurableLoginAuthorityProvider {
    suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority?

    /**
     * [serverId]'s saved login acting as [profileId], whether or not that server
     * is active, with an [AuthScopeSnapshot.savedLogin] scope. Null when that
     * server is not saved or holds no login. Whether [profileToken] still proves
     * [profileId] is the server's call.
     */
    suspend fun savedLoginAuthority(serverId: String, profileId: String, profileToken: String?): DurableLoginAuthority? = null
}
