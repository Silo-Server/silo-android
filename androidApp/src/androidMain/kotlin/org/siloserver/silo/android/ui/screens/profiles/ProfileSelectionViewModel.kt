package org.siloserver.silo.android.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.authorizedProfileToken
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileCommitResult
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileSelectionUiState(
    val profiles: List<Profile> = emptyList(),
    val canManageProfiles: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isManageMode: Boolean = false,
    /** Non-null when a PIN-protected profile was tapped and the dialog should show. */
    val pinDialogProfile: Profile? = null,
    val pinIsVerifying: Boolean = false,
    val pinError: String? = null,
    /** Set after a profile is successfully selected. */
    val selectedProfileId: String? = null,
    /** Non-null when a delete was requested and the confirm dialog should show. */
    val deleteDialogProfile: Profile? = null,
    /** The profile this session is signed in as — deleting it needs a
     *  stronger warning and clears the local selection first. */
    val activeProfileId: String? = null,
)

class ProfileSelectionViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSelectionUiState())
    val uiState: StateFlow<ProfileSelectionUiState> = _uiState.asStateFlow()

    /** Monotonic generation for PIN verification; see [onPinEntered]. */
    private var pinAttempt: Int = 0

    /** Monotonic generation for profile-list loads; see [loadProfiles]. */
    private var loadAttempt: Int = 0

    init {
        loadProfiles()
    }

    /**
     * The identity the displayed grid was fetched under.
     *
     * Every selection is qualified with it, protected or not. Passing null for
     * unprotected picks disabled the guard for exactly the case with no PIN
     * round trip to re-establish scope — so if another account became active
     * between the grid being accepted and the commit entering the barrier, one
     * account's profile id was written into the other's token slot.
     */
    private var gridScope: AuthScopeSnapshot? = null

    /**
     * @param clearError false keeps an existing error banner (e.g. a failed
     * delete's explanation) visible across the follow-up list refresh, which
     * would otherwise silently swallow it.
     */
    fun loadProfiles(clearError: Boolean = true) {
        val load = ++loadAttempt
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = if (clearError) null else it.error) }

            val scope = profileRepository.captureIdentityScope()
            val activeId = profileRepository.getActiveProfileId()
            val isAdmin = (authRepository?.getCurrentUser() as? ApiResult.Success)?.data?.role?.equals("admin", ignoreCase = true) == true
            val result = profileRepository.listProfiles()
            // Two separate reasons to drop this response: a newer load
            // superseded it, or the identity it was fetched under is gone.
            // The second is the one that matters — a stale grid lets the user
            // pick a profile from a session the app no longer holds.
            if (load != loadAttempt) return@launch
            if (!profileRepository.identityScopeUnchanged(scope)) {
                // The displayed grid is gone, so its scope must go with it.
                // Leaving a scope behind for an empty grid is stale metadata
                // that a later selection could be qualified against.
                gridScope = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profiles = emptyList(),
                        canManageProfiles = false,
                        isManageMode = false,
                        deleteDialogProfile = null,
                    )
                }
                return@launch
            }

            when (result) {
                is ApiResult.Success -> {
                    // The scope moves with the grid, and ONLY with it. Assigning
                    // it before this point meant a reload that failed under a
                    // NEW identity left the OLD grid on screen qualified by the
                    // NEW scope — so picking a profile from the old session was
                    // accepted as belonging to the new one. That is worse than
                    // the unguarded commit this was meant to fix.
                    gridScope = scope
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profiles = result.data,
                            activeProfileId = activeId,
                            canManageProfiles = isAdmin,
                            isManageMode = if (isAdmin) it.isManageMode else false,
                            deleteDialogProfile = if (isAdmin) it.deleteDialogProfile else null,
                        )
                    }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to load profiles" },
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Network error. Please try again.")
                    }
                }
            }
        }
    }

    fun toggleManageMode() {
        if (!_uiState.value.canManageProfiles) return
        _uiState.update { it.copy(isManageMode = !it.isManageMode) }
    }

    /**
     * Called when the user taps on a profile card.
     * If the profile has a PIN, opens the PIN dialog.
     * Otherwise, selects the profile immediately.
     */
    fun onProfileTapped(profile: Profile) {
        if (_uiState.value.isManageMode) {
            // In manage mode, tapping opens edit -- handled by the screen composable.
            return
        }
        // A click can already be queued when a scope mismatch clears the grid.
        // gridScope is null then, and passing it through would disable the
        // repository guard. Accept only profiles in the grid that is still
        // displayed; use the id because refreshed model instances need not be
        // referentially identical to the card's captured value.
        if (_uiState.value.profiles.none { it.id == profile.id }) return

        // Bump before branching: ANY accepted selection supersedes a
        // verification still in flight, including picking an unprotected
        // profile while a protected one is mid-verify.
        pinAttempt++

        if (profile.hasPin) {
            _uiState.update {
                it.copy(
                    pinDialogProfile = profile,
                    pinIsVerifying = false,
                    pinError = null,
                )
            }
        } else {
            // Qualified by the grid's scope. An unprotected pick has no PIN
            // round trip to re-establish identity, so without this it was the
            // one path that committed unguarded.
            selectProfile(profile.id, expectedScope = gridScope)
        }
    }

    /**
     * Verifies the entered PIN against the server for the profile in the dialog.
     */
    fun onPinEntered(pin: String) {
        val profile = _uiState.value.pinDialogProfile ?: return
        val attempt = ++pinAttempt

        viewModelScope.launch {
            _uiState.update { it.copy(pinIsVerifying = true, pinError = null) }

            // Pin the answer to the identity that was asked, not just to the
            // dialog target: the active scope can move underneath us.
            val scope = profileRepository.captureIdentityScope()
            val result = profileRepository.verifyPin(profile.id, pin)
            // The user can cancel (or tap a different profile) while the round
            // trip is in flight. Intent proven before a suspension point is not
            // intent after it, so re-check ownership before acting: committing
            // unconditionally meant Cancel still entered the profile.
            if (attempt != pinAttempt) return@launch

            when (result) {
                is ApiResult.Success -> {
                    val token = result.data.authorizedProfileToken()
                    if (token != null) {
                        _uiState.update { it.copy(pinIsVerifying = false, pinDialogProfile = null) }
                        selectProfile(profile.id, token, scope)
                    } else {
                        _uiState.update {
                            it.copy(pinIsVerifying = false, pinError = "Incorrect PIN")
                        }
                    }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            pinIsVerifying = false,
                            pinError = result.message.ifBlank { "Verification failed" },
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(pinIsVerifying = false, pinError = "Network error")
                    }
                }
            }
        }
    }

    fun dismissPinDialog() {
        // Bump the generation so an in-flight verification for the dismissed
        // profile can no longer commit.
        pinAttempt++
        _uiState.update {
            it.copy(pinDialogProfile = null, pinIsVerifying = false, pinError = null)
        }
    }

    /** Manage-mode delete tap — opens the confirmation dialog. */
    fun requestDeleteProfile(profile: Profile) {
        if (!_uiState.value.canManageProfiles) return
        _uiState.update { it.copy(deleteDialogProfile = profile) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteDialogProfile = null) }
    }

    fun confirmDeleteProfile() {
        val profile = _uiState.value.deleteDialogProfile ?: return
        _uiState.update { it.copy(deleteDialogProfile = null) }
        viewModelScope.launch {
            // Order matters: the DELETE itself is authorized by the signed-in
            // profile's X-Profile-Id/X-Profile-Token headers, so credentials
            // must stay intact until the server has answered. Only after a
            // successful delete of the signed-in profile do we clear the local
            // selection — BEFORE reloading, so the list refresh doesn't ride
            // the now-invalidated profile token (previously that errored and
            // rendered an empty list — "all profiles gone").
            when (val result = profileRepository.deleteProfile(profile.id)) {
                is ApiResult.Success -> {
                    if (profile.id == _uiState.value.activeProfileId) {
                        profileRepository.clearProfile()
                        _uiState.update { it.copy(activeProfileId = null) }
                    }
                    loadProfiles()
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(error = result.message.ifBlank { "Failed to delete profile" })
                    }
                    loadProfiles(clearError = false)
                }
                is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(error = "Network error") }
                }
            }
        }
    }

    /** Resets after the UI has navigated away. */
    fun onProfileSelectedConsumed() {
        _uiState.update { it.copy(selectedProfileId = null) }
    }

    private fun selectProfile(
        profileId: String,
        profileToken: String? = null,
        expectedScope: AuthScopeSnapshot? = null,
    ) {
        viewModelScope.launch {
            val result = profileRepository.selectProfile(profileId, profileToken, expectedScope)
            if (result == ProfileCommitResult.ScopeChanged) {
                // Someone else owns the identity now. Drop everything bound to
                // the identity we no longer have — a retained grid would let
                // the user pick a profile belonging to the previous session,
                // and that commit carries no scope to reject it.
                _uiState.update {
                    it.copy(
                        profiles = emptyList(),
                        activeProfileId = null,
                        selectedProfileId = null,
                        pinDialogProfile = null,
                        pinIsVerifying = false,
                        pinError = null,
                        deleteDialogProfile = null,
                    )
                }
                loadProfiles()
                return@launch
            }
            _uiState.update { it.copy(selectedProfileId = profileId) }
        }
    }
}
