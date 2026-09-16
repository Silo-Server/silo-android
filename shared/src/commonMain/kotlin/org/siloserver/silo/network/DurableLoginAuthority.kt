package org.siloserver.silo.network

/** A persisted login incarnation captured atomically with its live request scope. */
data class DurableLoginAuthority(val loginId: String, val scope: AuthScopeSnapshot) {
    override fun toString(): String = "DurableLoginAuthority(loginId=<redacted>, scope=$scope)"
}

/** Persistent implementations only; temporary credentials cannot supply durable authority. */
interface DurableLoginAuthorityProvider {
    suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority?
}
