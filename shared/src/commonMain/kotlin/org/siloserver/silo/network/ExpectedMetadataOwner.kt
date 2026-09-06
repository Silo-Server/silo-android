package org.siloserver.silo.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native admission expectation; access tokens stay live in the captured credential slot. */
fun AuthScopeSnapshot.matchesMetadataOwner(current: AuthScopeSnapshot?): Boolean =
    isSameIdentityAs(current) && serverUrl == current?.serverUrl && profileId == current?.profileId &&
        profileToken == current?.profileToken && credentialGenerationId == current?.credentialGenerationId

suspend fun TokenManager?.acceptsMetadataOwner(owner: AuthScopeSnapshot?, profileId: String): Boolean {
    currentCoroutineContext().ensureActive()
    if (owner == null) return true
    if (owner.profileId != profileId || profileId.isBlank() || this == null) return false
    val current = snapshotCurrentScope()
    currentCoroutineContext().ensureActive()
    return owner.matchesMetadataOwner(current)
}
