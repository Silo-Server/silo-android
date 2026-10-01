package org.siloserver.silo.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.StaleProfileReport
import org.siloserver.silo.network.TokenManager

/**
 * The user must pick a profile (and enter its PIN) again because the server
 * stopped accepting the active profile's proof on server [serverId].
 *
 * The prompt stays valid while that server is active, signed in, and has no
 * profile selected. Signing out, switching servers, or picking a profile ends
 * it. Other identity transitions (a remote-playback overlay starting or ending,
 * removing another server) leave the profile cleared, so they must not end it.
 *
 * [sequence] tells two prompts for the same server apart.
 */
data class ProfileVerificationPrompt(
    val serverId: String?,
    val sequence: Long,
)

/** What the navigation layer does with a pending [ProfileVerificationPrompt]. */
enum class ProfilePromptAction {
    /** Send the user to profile selection now. */
    Navigate,

    /** Keep the prompt until the user leaves the current destination. */
    Defer,

    /**
     * The user is already on the profile picker or one of its screens. Consume
     * the prompt without navigating, so a PIN being typed is not thrown away;
     * the picker reloads itself from [ProfileVerificationRecovery.profileCleared].
     */
    AlreadyThere,

    /** The prompt no longer applies; consume it without navigating. */
    Drop,
}

/**
 * Turns stale-profile refusals into one profile-selection prompt.
 *
 * Any v2 request can come back 403 `profile_verification_required` after an
 * administrator changes the account's access: the server stops accepting PIN
 * profile tokens minted before the change. Without this every screen showed a
 * failed load. The recovery clears the stale profile selection (never the
 * account session) through [ProfileRepository.clearStaleProfile], which ignores
 * refusals for any identity other than the active one, and then raises
 * [pending] for the phone and TV navigation to act on.
 *
 * Background work (downloads, outbox replay, Watch Next) reaches this too when
 * it ran on the active identity, because that identity is just as stale. It
 * never navigates by itself: navigation reads [pending] and defers while the
 * player is in front, so a background refusal cannot pull the user out of
 * playback.
 */
class ProfileVerificationRecovery(
    private val signals: AccessChangeSignals,
    private val tokenManager: TokenManager,
    private val profileRepository: ProfileRepository,
    scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<ProfileVerificationPrompt?>(null)

    /** The prompt waiting for the navigation layer, or null. */
    val pending: StateFlow<ProfileVerificationPrompt?> = _pending.asStateFlow()

    private val _profileCleared = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Fires each time a stale profile is cleared. The profile pickers reload on
     * it: a list fetched with the stale token failed, and the next one is sent
     * without it.
     */
    val profileCleared: SharedFlow<Unit> = _profileCleared.asSharedFlow()

    private var sequence = 0L

    init {
        scope.launch {
            // Serial on purpose: the first report clears the profile, and every
            // later one for the same identity then finds nothing to clear.
            signals.staleProfileReports.collect { report ->
                try {
                    handle(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failing identity gate must not crash the app or end the
                    // collection; the next refusal tries again.
                }
            }
        }
    }

    internal suspend fun handle(report: StaleProfileReport) {
        if (!profileRepository.clearStaleProfile(report)) return
        _pending.value = ProfileVerificationPrompt(
            serverId = tokenManager.snapshotCurrentScope()?.serverId ?: tokenManager.getCurrentServerId(),
            sequence = ++sequence,
        )
        _profileCleared.tryEmit(Unit)
    }

    /**
     * Whether [prompt] still applies: the same server is active and signed in,
     * and no profile has been selected since. Call only while no
     * remote-playback overlay is installed (the overlay reports its own
     * identity, not the saved one).
     */
    suspend fun isCurrent(prompt: ProfileVerificationPrompt): Boolean {
        if (tokenManager.getAccessToken().isNullOrBlank()) return false
        val scope = tokenManager.snapshotCurrentScope()
        val serverId = scope?.serverId ?: tokenManager.getCurrentServerId()
        val profileId = if (scope != null) scope.profileId else tokenManager.getProfileId()
        return serverId == prompt.serverId && profileId.isNullOrBlank()
    }

    /**
     * Decides what to do with [prompt] while [currentRoute] is displayed.
     * [deferRoutes] are destinations the prompt must not interrupt: playback,
     * and the sign-in and server screens where no profile is in use.
     * [pickerRoutes] are the profile picker and its create and edit screens.
     */
    suspend fun actionFor(
        prompt: ProfileVerificationPrompt,
        currentRoute: String?,
        deferRoutes: Set<String>,
        pickerRoutes: Set<String> = emptySet(),
    ): ProfilePromptAction = when {
        // A remote-playback overlay owns identity until it ends; decide after.
        tokenManager.hasTemporaryScope() -> ProfilePromptAction.Defer
        !isCurrent(prompt) -> ProfilePromptAction.Drop
        currentRoute == null || currentRoute in deferRoutes -> ProfilePromptAction.Defer
        currentRoute in pickerRoutes -> ProfilePromptAction.AlreadyThere
        else -> ProfilePromptAction.Navigate
    }

    /** Marks [prompt] handled; a newer prompt raised meanwhile is kept. */
    fun consume(prompt: ProfileVerificationPrompt) {
        _pending.compareAndSet(prompt, null)
    }
}
