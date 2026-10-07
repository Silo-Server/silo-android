package org.siloserver.silo.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.VerifyPinResponse
import org.siloserver.silo.model.profile.authorizedProfileToken
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.HouseholdManager
import org.siloserver.silo.network.api.isSameAccountAs

/**
 * The profile picker's manage mode: household management acting as the
 * account's primary profile, which is the only profile the server lets create,
 * edit, or delete the others.
 *
 * The primary's profile token is held in memory for the session only. It is
 * never selected or persisted, and the PIN itself is never stored.
 *
 * A profile token is valid only for the account's access-policy revision it was
 * issued under, and the server bumps that revision whenever an update touches a
 * PIN, child flag, rating, advisory, library, or quality limit. So the first
 * save of that kind makes the held token stale. [run] answers the resulting
 * `profile_verification_required` by asking for the primary's PIN again
 * ([reverify]), replacing the token, and retrying the call once.
 */
class HouseholdManagementSession internal constructor(
    private val verifyPin: suspend (profileId: String, pin: String) -> ApiResult<VerifyPinResponse>,
    private val captureScope: suspend () -> AuthScopeSnapshot?,
) {
    /** The PIN prompt shown while a stale primary token is being replaced. */
    data class Reverify(
        val profile: Profile,
        val isVerifying: Boolean = false,
        val error: String? = null,
        /** Bumped on each rejected PIN so the entry clears. */
        val errorCount: Int = 0,
    )

    private class Held(val primary: Profile, val manager: HouseholdManager)

    private val held = MutableStateFlow<Held?>(null)
    private val _reverify = MutableStateFlow<Reverify?>(null)
    private val pinEntries = Channel<String?>(Channel.CONFLATED)
    private val renewLock = Mutex()

    private val _isActive = MutableStateFlow(false)

    /** Whether a management session is running; it drops to false when the user cancels a re-prompt. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Non-null while the primary's PIN is needed again; render the PIN prompt for it. */
    val reverify: StateFlow<Reverify?> = _reverify.asStateFlow()

    /** The manager management calls act as, or null outside a session. */
    val manager: HouseholdManager? get() = held.value?.manager

    /**
     * Starts a session as [primary]. [profileToken] is the token `verify-pin`
     * issued for it (null when it has no PIN); [scope] is the identity that
     * verification ran under.
     */
    fun begin(primary: Profile, profileToken: String?, scope: AuthScopeSnapshot?) {
        setHeld(Held(primary, HouseholdManager(primary.id, profileToken, scope)))
    }

    /**
     * Ends the session, abandoning any open re-prompt. The null entry is sent
     * unconditionally: a re-prompt that is about to open (its held check
     * already passed) reads it and closes instead of waiting for input.
     */
    fun end() {
        setHeld(null)
        pinEntries.trySend(null)
    }

    /** A PIN typed into the [reverify] prompt. */
    fun submitPin(pin: String) {
        if (_reverify.value?.isVerifying == false) pinEntries.trySend(pin)
    }

    /**
     * Cancel on the [reverify] prompt: ends the session without retrying. The
     * session ends at once, so a verification already in flight cannot renew
     * the manager and retry the call after the user cancelled.
     */
    fun cancelReverify() {
        if (_reverify.value != null) end()
    }

    /**
     * Runs one management [call] as the held manager. When the server says the
     * primary's token is no longer valid, asks for the PIN again and retries
     * the call once with the new token. Cancel there ends the session and
     * returns [cancelledError].
     */
    suspend fun <T> run(call: suspend (HouseholdManager?) -> ApiResult<T>): ApiResult<T> {
        val used = manager
        val first = call(used)
        if (used == null || !first.needsProfileVerification()) return first
        val renewed = renew(used) ?: return cancelledError()
        return call(renewed)
    }

    private suspend fun renew(failed: HouseholdManager): HouseholdManager? = renewLock.withLock {
        // Drain before the held check: an end() after this point leaves its
        // null in the channel, so the prompt below closes on it.
        while (pinEntries.tryReceive().isSuccess) Unit
        val current = held.value ?: return@withLock null
        // Another call already asked for the PIN while this one waited.
        if (current.manager !== failed) return@withLock current.manager
        _reverify.value = Reverify(current.primary)
        try {
            askUntilVerified(current)
        } finally {
            _reverify.value = null
        }
    }

    private suspend fun askUntilVerified(current: Held): HouseholdManager? {
        while (true) {
            val pin = pinEntries.receive()
            if (pin == null) {
                if (held.value === current) setHeld(null)
                return null
            }
            _reverify.update { it?.copy(isVerifying = true, error = null) }
            val scope = captureScope()
            if (!current.isSameAccountAs(scope)) return endFor(current)
            val result = verifyPin(current.primary.id, pin)
            if (held.value !== current) return null
            // The new token must come from the account the session began on.
            if (!current.isSameAccountAs(captureScope())) return endFor(current)
            val token = (result as? ApiResult.Success)?.data?.authorizedProfileToken()
            if (token != null) {
                val renewed = Held(current.primary, HouseholdManager(current.primary.id, token, scope))
                setHeld(renewed)
                return renewed.manager
            }
            val message = when (result) {
                is ApiResult.Success -> "Wrong PIN. Try again."
                is ApiResult.Error -> result.message.ifBlank { "Incorrect PIN" }
                is ApiResult.NetworkError -> "Network error. Please try again."
            }
            _reverify.update { it?.copy(isVerifying = false, error = message, errorCount = it.errorCount + 1) }
        }
    }

    private fun Held.isSameAccountAs(scope: AuthScopeSnapshot?): Boolean =
        manager.scope?.isSameAccountAs(scope) ?: true

    private fun endFor(current: Held): HouseholdManager? {
        if (held.value === current) setHeld(null)
        return null
    }

    private fun setHeld(value: Held?) {
        held.value = value
        _isActive.value = value != null
    }

    companion object {
        private val VerificationCodes = setOf("profile_verification_required", "profile_unverified")

        internal fun ApiResult<*>.needsProfileVerification(): Boolean =
            this is ApiResult.Error && code == 403 && error in VerificationCodes

        internal fun cancelledError(): ApiResult.Error =
            ApiResult.Error(403, "profile_verification_required", "Enter the primary profile's PIN to manage profiles.")
    }
}
