package org.siloserver.silo.repository

import org.siloserver.silo.model.profile.CreateProfileRequest
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.model.profile.VerifyPinResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.map

/** Outcome of a scope-guarded profile commit. */
enum class ProfileCommitResult {
    /** Identity was written. */
    Committed,

    /**
     * The commit was refused because this identity is not ours to write:
     * either the scope moved between capture and commit, or a temporary
     * remote-playback overlay owns identity right now. Nothing was written.
     */
    ScopeChanged,
}

open class ProfileRepository(
    private val profileApi: ProfileApi,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry? = null,
    private val notificationsRepository: NotificationsRepository? = null,
    private val requestsRepository: RequestsRepository? = null,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) {
    /** Lists all profiles for the current user. */
    open suspend fun listProfiles(): ApiResult<List<Profile>> =
        profileApi.listProfiles().map { it.profiles }

    /** Creates a new profile. */
    open suspend fun createProfile(request: CreateProfileRequest): ApiResult<Profile> =
        profileApi.createProfile(request)

    /** Updates an existing profile. */
    open suspend fun updateProfile(id: String, request: UpdateProfileRequest): ApiResult<Profile> =
        profileApi.updateProfile(id, request)

    suspend fun updateActiveProfile(request: UpdateProfileRequest): ApiResult<Profile> {
        val profileId = getActiveProfileId()
            ?: return ApiResult.Error(
                code = 400,
                error = "bad_request",
                message = "No active profile selected",
            )
        return updateProfile(profileId, request)
    }

    /** Deletes a profile by ID. */
    suspend fun deleteProfile(id: String): ApiResult<Unit> =
        profileApi.deleteProfile(id)

    /**
     * Verifies a profile's PIN.
     *
     * This deliberately does NOT touch [TokenManager]. Verification is a
     * question, not a commitment: the answer is only worth acting on if the
     * caller still wants this profile when it arrives. Persisting the token
     * here wrote it into whatever server slot happened to be active by then,
     * so cancelling mid-flight (or switching servers) could install one
     * server's profile token as another's. Callers commit the result through
     * [selectProfile], which binds id and token together in one transition.
     */
    suspend fun verifyPin(profileId: String, pin: String): ApiResult<VerifyPinResponse> =
        profileApi.verifyPin(profileId, pin)

    /**
     * Selects a profile as the active profile.
     *
     * Persists the profile id on the active [TokenManager] slot AND on the
     * matching [ServerRegistry] entry — the latter is what restores the
     * "last used profile" when the user hops back to this server.
     *
     * [profileToken] is the artifact `verify-pin` just issued for *this*
     * profile, or null for an unprotected one. Id and token are written as one
     * stored identity: a profile token is bound server-side to a single profile
     * id, and carrying the previous profile's token into the new selection made
     * every request claim one profile while presenting another's proof. Phone
     * hit that on the ordinary protected-A → unprotected-B switch (TV cleared
     * first, so only one client was wrong).
     *
     * Scope note: the WRITE is atomic and so is what survives a crash, but
     * readers still fetch id and token through separate calls
     * ([TokenManager.getProfileId] / [TokenManager.getProfileToken]), so a
     * request assembled exactly across a switch can still pair an old id with a
     * new token. Closing that needs a combined accessor and a migration of
     * every paired reader.
     */
    suspend fun selectProfile(
        profileId: String,
        profileToken: String? = null,
        expectedScope: AuthScopeSnapshot? = null,
    ): ProfileCommitResult {
        var result = ProfileCommitResult.Committed
        // Read the barrier generation before entering the transition. `changing`
        // runs its gates, then bumps the generation, then runs this block — so
        // by the time we are inside, the live generation is already this
        // transition's own. Comparing the captured scope against it directly
        // would report "changed" on every single selection.
        val generationBeforeTransition = identityTransitions.generation.value
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            // Re-check INSIDE the transition: a scope captured before the PIN
            // round trip proves nothing unless it still holds at the moment of
            // the write. Remote playback can install a temporary identity
            // mid-flight, and committing there would put this server's profile
            // proof into an overlay that belongs to a different session.
            // A remote-playback overlay owns identity while it exists, and it
            // is not this user's session to repoint. This check is deliberately
            // independent of [expectedScope]: an unprotected selection carries
            // no scope to compare, and an already-dispatched tap can land after
            // the overlay installs.
            if (tokenManager.hasTemporaryScope()) {
                result = ProfileCommitResult.ScopeChanged
                return@changing
            }
            if (!identityScopeStillHolds(expectedScope, generationBeforeTransition)) {
                result = ProfileCommitResult.ScopeChanged
                return@changing
            }
            tokenManager.setProfileIdentity(profileId, profileToken)
            val activeServerId = tokenManager.getCurrentServerId()
            if (activeServerId != null) {
                // Known, accepted window: this is a SECOND durable edit, so a
                // process death between it and the identity write above leaves
                // the registry naming the old profile while the token manager
                // (and therefore every request header) names the new one.
                // Startup prefers the registry, so the next launch can look
                // like the old profile while authenticating as the new one.
                // Not the same class as the id/token mismatch fixed above —
                // that one sent mismatched credentials on every request — but
                // closing it means making one of the two authoritative.
                serverRegistry?.setProfileId(activeServerId, profileId)
            }
            notificationsRepository?.reset()
            requestsRepository?.reset()
            _profileSwitches.tryEmit(Unit)
        }
        return result
    }

    /**
     * Capture the identity scope a PIN verification is about to be asked
     * against, for later hand-off to [selectProfile]. Null when the manager
     * does not model scopes, which keeps the guard inert rather than failing
     * closed on something it never recorded.
     */
    suspend fun captureIdentityScope(): AuthScopeSnapshot? =
        tokenManager.snapshotCurrentScope()

    /**
     * Whether [expected] is still the live identity, for callers that are not
     * inside an identity transition (so no generation offset applies).
     *
     * Used to discard a profile list fetched under an identity that has since
     * been replaced — a stale grid lets the user pick a profile belonging to a
     * session the app no longer holds.
     */
    suspend fun identityScopeUnchanged(expected: AuthScopeSnapshot?): Boolean {
        if (expected == null) return true
        return expected.isSameIdentityAs(tokenManager.snapshotCurrentScope())
    }

    /**
     * Whether the identity active *now* is still the one [expected] was
     * captured from.
     *
     * [generationBeforeTransition] is the barrier generation read immediately
     * before the enclosing `changing` block, which bumps it on entry. So:
     *
     *  - `expected.identityGeneration == generationBeforeTransition` says
     *    nothing moved between capturing the scope and starting this commit;
     *  - `current.identityGeneration == generationBeforeTransition + 1` says
     *    the only transition since is this one, so nobody slipped in while we
     *    were waiting on the barrier's mutex.
     *
     * A null [expected] means the caller never captured a scope (or the manager
     * does not model them), so the guard stays inert rather than failing closed
     * on information it never recorded. But once a scope WAS captured, a
     * missing current scope means it is gone, not unsupported — that fails
     * closed.
     *
     * Deliberately a repository function rather than a `TokenManager` default
     * method: a default that calls another overridable member runs against the
     * delegate under interface delegation, so wrappers would silently get the
     * base behaviour.
     */
    private suspend fun identityScopeStillHolds(
        expected: AuthScopeSnapshot?,
        generationBeforeTransition: Long,
    ): Boolean {
        if (expected == null) return true
        if (expected.identityGeneration != generationBeforeTransition) return false
        val current = tokenManager.snapshotCurrentScope() ?: return false
        return current.serverId == expected.serverId &&
            current.identityGeneration == generationBeforeTransition + 1 &&
            current.credentialEpoch == expected.credentialEpoch
    }

    private val _profileSwitches = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires on every [selectProfile]. Consumers that must react to profile
     * switches (realtime socket reconnects, scope-sensitive caches) observe
     * this directly instead of piggybacking on another feature's reset
     * signal.
     */
    val profileSwitches: kotlinx.coroutines.flow.SharedFlow<Unit> = _profileSwitches

    /** Returns the currently active profile ID, if any. */
    open suspend fun getActiveProfileId(): String? =
        tokenManager.getProfileId()

    /**
     * Returns the currently active [Profile] by looking up the stored active
     * id against [listProfiles]. Intended for read-mostly consumers (e.g. the
     * player needing `language` / `subtitleLanguage` for track selection) —
     * callers that need to react to profile changes should observe the state
     * they drive the switch from.
     */
    suspend fun getActiveProfile(): Profile? {
        val activeId = getActiveProfileId() ?: return null
        val profiles = when (val result = listProfiles()) {
            is ApiResult.Success -> result.data
            else -> return null
        }
        return profiles.firstOrNull { it.id == activeId }
    }

    suspend fun getActiveProfileResult(): ApiResult<Profile> {
        val activeId = getActiveProfileId()
            ?: return ApiResult.Error(
                code = 400,
                error = "bad_request",
                message = "No active profile selected",
            )
        return when (val result = listProfiles()) {
            is ApiResult.Success -> {
                result.data.firstOrNull { it.id == activeId }?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error(
                        code = 404,
                        error = "not_found",
                        message = "Active profile not found",
                    )
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /** Clears the active profile selection and its token. */
    suspend fun clearProfile() {
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            val activeServerId = tokenManager.getCurrentServerId()
            tokenManager.setProfileIdentity(null, null)
            if (activeServerId != null) {
                serverRegistry?.setProfileId(activeServerId, null)
            }
        }
    }
}
