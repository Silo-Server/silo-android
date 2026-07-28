package org.siloserver.silo.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.onboarding.OnboardingFlow
import org.siloserver.silo.model.onboarding.OnboardingStep
import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.OnboardingRepository
import org.siloserver.silo.repository.ProfileRepository

/** Step kinds this client can render; anything else is dropped at load. */
private val KNOWN_KINDS = setOf("welcome", "feature_card", "setting_choice", "handoff")

data class OnboardingTourUiState(
    val isLoading: Boolean = true,
    /** Empty after load with [finished] set = nothing to show. */
    val steps: List<OnboardingStep> = emptyList(),
    val tourId: String = "",
    val currentIndex: Int = 0,
    /**
     * setting_choice values picked (or defaulted) but not yet persisted.
     * Written when the user advances past the step, so what the card shows
     * as selected is exactly what gets saved.
     */
    val pendingChoices: Map<String, String> = emptyMap(),
    val finished: Boolean = false,
)

/**
 * Drives the server-driven first-run tour. Progress and completion post to
 * the server per profile, so finishing here silences the web and TV too.
 * setting_choice steps write through the existing profile-update path.
 */
class OnboardingTourViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val profileRepository: ProfileRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val tokenManager: TokenManager,
    private val localCache: OnboardingTourLocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingTourUiState())
    val uiState: StateFlow<OnboardingTourUiState> = _uiState.asStateFlow()

    private var loadStarted = false

    fun load() {
        // The screen calls this from a LaunchedEffect that re-runs on every
        // composition restart (rotation, theme change); without the guard a
        // mid-tour user would be re-fetched back to step 1.
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            // Known-done locally: skip the network entirely. The flag is only
            // ever set from a server-confirmed done state or our own
            // complete/skip, so trusting it can't hide a pending tour.
            if (localCache.isDone(tokenManager.getCurrentServerId(), tokenManager.getProfileId())) {
                _uiState.update { it.copy(isLoading = false, finished = true) }
                return@launch
            }
            // Fetch state and manifest together — the manifest is discarded
            // when state says done, but that waste is cheaper than serializing
            // two round trips in front of first render.
            val stateDeferred = async { onboardingRepository.getState() }
            val flowDeferred = async { onboardingRepository.getFlow(surface = "phone") }
            when (val state = stateDeferred.await()) {
                is ApiResult.Success -> {
                    if (state.data.done) {
                        markDoneLocally()
                        flowDeferred.cancel()
                        _uiState.update { it.copy(isLoading = false, finished = true) }
                        return@launch
                    }
                }
                // On any error, skip the tour rather than block first run.
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    flowDeferred.cancel()
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                    return@launch
                }
            }
            when (val flow = flowDeferred.await()) {
                is ApiResult.Success -> applyFlow(flow.data)
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                }
            }
        }
    }

    private suspend fun applyFlow(flow: OnboardingFlow) {
        val steps = flow.steps.filter { it.kind in KNOWN_KINDS }
        if (steps.isEmpty()) {
            // Nothing renderable: mark complete so we never loop.
            markDoneLocally()
            _uiState.update { it.copy(isLoading = false, finished = true) }
            withContext(NonCancellable) {
                onboardingRepository.complete(flow.tourId, null)
            }
            return
        }
        // Seed each setting_choice with its manifest default so a user who
        // accepts what the card already shows still has it persisted on
        // advance.
        val defaults = steps
            .filter { it.kind == "setting_choice" }
            .mapNotNull { step -> step.setting?.default?.let { step.id to it } }
            .toMap()
        _uiState.update {
            it.copy(
                isLoading = false,
                steps = steps,
                tourId = flow.tourId,
                currentIndex = 0,
                pendingChoices = defaults,
            )
        }
    }

    fun onAdvance() {
        val current = _uiState.value
        persistChoiceIfAny(current.steps.getOrNull(current.currentIndex))
        val next = current.currentIndex + 1
        if (next >= current.steps.size) {
            finish(skipped = false, persistCurrentChoice = false)
            return
        }
        viewModelScope.launch {
            onboardingRepository.recordStep(current.tourId, current.steps[next].id)
        }
        _uiState.update { it.copy(currentIndex = next) }
    }

    fun onBack() {
        _uiState.update { it.copy(currentIndex = (it.currentIndex - 1).coerceAtLeast(0)) }
    }

    fun onSkip() = finish(skipped = true, persistCurrentChoice = false)

    fun onFinish() = finish(skipped = false, persistCurrentChoice = true)

    private fun finish(skipped: Boolean, persistCurrentChoice: Boolean) {
        val current = _uiState.value
        if (persistCurrentChoice) {
            persistChoiceIfAny(current.steps.getOrNull(current.currentIndex))
        }
        markDoneLocally()
        viewModelScope.launch {
            val lastStep = current.steps.getOrNull(current.currentIndex)?.id
            // finished=true (below) navigates away with popUpTo, which clears
            // this ViewModel and cancels its scope — the POST must survive
            // that or the server never learns the tour ended and re-shows it.
            withContext(NonCancellable) {
                if (skipped) {
                    onboardingRepository.skip(current.tourId, lastStep)
                } else {
                    onboardingRepository.complete(current.tourId, lastStep)
                }
            }
        }
        _uiState.update { it.copy(finished = true) }
    }

    private fun markDoneLocally() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                localCache.markDone(tokenManager.getCurrentServerId(), tokenManager.getProfileId())
            }
        }
    }

    /** Records a tapped option locally; nothing is written until advance. */
    fun onSettingChosen(step: OnboardingStep, value: String) {
        _uiState.update { it.copy(pendingChoices = it.pendingChoices + (step.id to value)) }
    }

    /**
     * Writes one setting_choice value. UpdateProfileRequest is typed per
     * field, so the manifest's string key maps onto the matching field;
     * unknown keys (a newer server) are ignored rather than failing the
     * tour. Only profile_field targets exist for phones today.
     */
    private fun persistChoiceIfAny(step: OnboardingStep?) {
        val spec = step?.setting ?: return
        if (spec.target != "profile_field") return
        val value = _uiState.value.pendingChoices[step.id] ?: return
        val request = when (spec.key) {
            "quality_preference" -> UpdateProfileRequest(qualityPreference = value)
            "subtitle_language" -> UpdateProfileRequest(subtitleLanguage = value)
            "subtitle_mode" -> UpdateProfileRequest(subtitleMode = value)
            "auto_skip_intro" -> UpdateProfileRequest(autoSkipIntro = value.toBoolean())
            "auto_skip_credits" -> UpdateProfileRequest(autoSkipCredits = value.toBoolean())
            else -> return
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                // Android playback and the Settings screen read quality from
                // the local player settings store, not the profile field —
                // write both or the choice the tour just showed has no
                // visible effect anywhere in this app.
                if (spec.key == "quality_preference") {
                    playerSettingsStore.setPreferredQuality(value)
                }
                profileRepository.updateActiveProfile(request)
            }
        }
    }
}
