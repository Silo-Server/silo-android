package org.siloserver.silo.android.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.authorizedProfileToken
import org.siloserver.silo.model.profile.householdPrimary
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileCommitResult
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.ProfileVerificationRecovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.ui.marquee.SignInHandoff

data class ProfileSelectionUiState(
    val profiles: List<Profile> = emptyList(),
    val canManageProfiles: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isManageMode: Boolean = false,
    /** Non-null when a PIN-protected profile was tapped and the dialog should show. */
    val pinDialogProfile: Profile? = null,
    /** The open PIN prompt is the primary profile's, unlocking manage mode rather than selecting it. */
    val pinForManagement: Boolean = false,
    val pinIsVerifying: Boolean = false,
    val pinError: String? = null,
    /** Bumped on each rejected PIN so the dots shake and the entry clears. */
    val pinErrorCount: Int = 0,
    /** The signed-in account's username, shown under the title; null until read. */
    val accountName: String? = null,
    /** A one-profile household is being opened straight after sign-in; the picker stays hidden. */
    val openingOnlyProfile: Boolean = false,
    /** Set after a profile is successfully selected. */
    val selectedProfileId: String? = null,
    /** Non-null when a delete was requested and the confirm dialog should show. */
    val deleteDialogProfile: Profile? = null,
    /** The profile this session is signed in as — deleting it needs a
     *  stronger warning and clears the local selection first. */
    val activeProfileId: String? = null,
    /** Manage mode just started for "Add profile"; the screen opens the add form and consumes this. */
    val openAddProfile: Boolean = false,
)

class ProfileSelectionViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository? = null,
    private val consumeSkipsSingleProfile: () -> Boolean = SignInHandoff::consumeSkipsSingleProfilePicker,
    private val profileVerificationRecovery: ProfileVerificationRecovery? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSelectionUiState())
    val uiState: StateFlow<ProfileSelectionUiState> = _uiState.asStateFlow()

    /** Monotonic generation for PIN verification; see [onPinEntered]. */
    private var pinAttempt: Int = 0

    /** Monotonic generation for profile-list loads; see [loadProfiles]. */
    private var loadAttempt: Int = 0

    /**
     * Read once, on the first load: a sign-in that just finished asked to
     * skip a one-person picker (see [SignInHandoff]).
     */
    private var skipsSingleProfile: Boolean? = null

    /** "Add profile" asked for manage mode; open the add form once it starts. */
    private var addAfterUnlock = false

    init {
        loadProfiles()
        reloadWhenUpdateRequiredLifts()
        reloadWhenStaleProfileCleared()
        leaveManageModeWhenSessionEnds()
    }

    /**
     * A load sent with a profile token the server no longer accepts fails with
     * `profile_verification_required`. The recovery then clears that profile,
     * and the grid reloads without it instead of leaving the error up.
     */
    private fun reloadWhenStaleProfileCleared() {
        val recovery = profileVerificationRecovery ?: return
        viewModelScope.launch {
            recovery.profileCleared.collect { loadProfiles() }
        }
    }

    /** Cancel on a re-prompt for the primary's PIN ends the session; manage mode goes with it. */
    private fun leaveManageModeWhenSessionEnds() {
        viewModelScope.launch {
            profileRepository.householdManagement.isActive.collect { active ->
                if (!active && _uiState.value.isManageMode) {
                    _uiState.update { it.copy(isManageMode = false, deleteDialogProfile = null) }
                }
            }
        }
    }

    /**
     * The admin lookup in [loadProfiles] is a gated v2 call. On launch the
     * stored verdict can be a stale UPDATE_REQUIRED (server upgraded since),
     * which the gate rejects without a request; once the launch probe
     * records V2 the grid must be reloaded so the manage affordances match
     * the real answer.
     */
    private fun reloadWhenUpdateRequiredLifts() {
        val contracts = authRepository?.activeServerContractFlow ?: return
        viewModelScope.launch {
            var previous: ServerContract? = null
            contracts.collect { contract ->
                if (previous == ServerContract.UPDATE_REQUIRED && contract == ServerContract.V2) {
                    loadProfiles()
                }
                previous = contract
            }
        }
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
            val skipSingle = skipsSingleProfile ?: consumeSkipsSingleProfile().also { skipsSingleProfile = it }
            val user = (authRepository?.getCurrentUser() as? ApiResult.Success)?.data
            val isAdmin = user?.role?.equals("admin", ignoreCase = true) == true
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
                profileRepository.householdManagement.end()
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
                    // A household with one profile and no PIN doesn't need a
                    // picker after signing in: open it, once.
                    val only = result.data.singleOrNull()?.takeIf { skipSingle && !it.hasPin }
                    skipsSingleProfile = false
                    if (!isAdmin) profileRepository.householdManagement.end()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            openingOnlyProfile = only != null,
                            accountName = user?.username ?: it.accountName,
                            profiles = result.data,
                            activeProfileId = activeId,
                            canManageProfiles = isAdmin,
                            isManageMode = if (isAdmin) it.isManageMode else false,
                            deleteDialogProfile = if (isAdmin) it.deleteDialogProfile else null,
                        )
                    }
                    if (only != null) onProfileTapped(only)
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

    /**
     * Entering manage mode acts as the household's primary profile, the only
     * one the server lets manage the others, whichever profile this device
     * last used. A PIN-locked primary is verified first; Cancel there leaves
     * manage mode off. The primary is never selected or remembered.
     */
    fun toggleManageMode() {
        val state = _uiState.value
        if (!state.canManageProfiles) return
        if (state.isManageMode) {
            profileRepository.householdManagement.end()
            _uiState.update { it.copy(isManageMode = false) }
            return
        }
        enterManageMode(thenAdd = false)
    }

    /**
     * "Add profile" creates a household profile, so it needs the same primary
     * profile step as Manage; the add form opens once manage mode is on.
     */
    fun requestAddProfile() {
        val state = _uiState.value
        if (!state.canManageProfiles) return
        if (state.isManageMode && profileRepository.householdManagement.isActive.value) {
            _uiState.update { it.copy(openAddProfile = true) }
        } else {
            enterManageMode(thenAdd = true)
        }
    }

    fun onAddProfileConsumed() {
        _uiState.update { it.copy(openAddProfile = false) }
    }

    private fun enterManageMode(thenAdd: Boolean) {
        val primary = _uiState.value.profiles.householdPrimary()
        if (primary == null) {
            _uiState.update { it.copy(error = "Couldn't find the primary profile needed to manage profiles.") }
            return
        }
        pinAttempt++
        addAfterUnlock = thenAdd
        if (primary.hasPin) {
            _uiState.update {
                it.copy(pinDialogProfile = primary, pinForManagement = true, pinIsVerifying = false, pinError = null)
            }
            return
        }
        profileRepository.householdManagement.begin(primary, profileToken = null, scope = gridScope)
        onManageModeStarted()
    }

    private fun onManageModeStarted() {
        val add = addAfterUnlock
        addAfterUnlock = false
        _uiState.update { it.copy(isManageMode = true, openAddProfile = add) }
    }

    override fun onCleared() {
        profileRepository.householdManagement.end()
        super.onCleared()
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
                    pinForManagement = false,
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
        val forManagement = _uiState.value.pinForManagement
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
                    if (token != null && forManagement) {
                        // An account or server change during the round trip
                        // voids this answer; the reload shows the new identity.
                        if (!profileRepository.identityScopeUnchanged(scope)) {
                            dismissPinDialog()
                            loadProfiles()
                            return@launch
                        }
                        // Kept for manage mode only; the device's selection is untouched.
                        profileRepository.householdManagement.begin(profile, token, scope)
                        _uiState.update { it.copy(pinIsVerifying = false, pinDialogProfile = null, pinForManagement = false) }
                        onManageModeStarted()
                    } else if (token != null) {
                        _uiState.update { it.copy(pinIsVerifying = false, pinDialogProfile = null) }
                        selectProfile(profile.id, token, scope)
                    } else {
                        _uiState.update {
                            it.copy(pinIsVerifying = false, pinError = "Wrong PIN. Try again.", pinErrorCount = it.pinErrorCount + 1)
                        }
                    }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            pinIsVerifying = false,
                            pinError = result.message.ifBlank { "Verification failed" },
                            pinErrorCount = it.pinErrorCount + 1,
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(pinIsVerifying = false, pinError = "Network error", pinErrorCount = it.pinErrorCount + 1)
                    }
                }
            }
        }
    }

    fun dismissPinDialog() {
        addAfterUnlock = false
        // Bump the generation so an in-flight verification for the dismissed
        // profile can no longer commit.
        pinAttempt++
        _uiState.update {
            it.copy(pinDialogProfile = null, pinForManagement = false, pinIsVerifying = false, pinError = null)
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
            // Order matters: the DELETE is authorized by the household
            // manager's (primary profile's) headers, but the signed-in
            // profile's credentials stay intact until the server has
            // answered. Only after a successful delete of the signed-in
            // profile do we clear the local
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
            if (result != ProfileCommitResult.Committed) _uiState.update { it.copy(openingOnlyProfile = false) }
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
