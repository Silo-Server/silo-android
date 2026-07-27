package org.siloserver.silo.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.onboarding.OnboardingFlow
import org.siloserver.silo.model.onboarding.OnboardingStep
import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.OnboardingRepository
import org.siloserver.silo.repository.ProfileRepository

/** Step kinds this client can render; anything else is dropped at load. */
private val KNOWN_KINDS = setOf("welcome", "feature_card", "setting_choice", "handoff")

data class OnboardingTourUiState(
    val isLoading: Boolean = true,
    /** null after load = nothing to show (done already, feature off, or error). */
    val steps: List<OnboardingStep> = emptyList(),
    val tourId: String = "",
    val currentIndex: Int = 0,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingTourUiState())
    val uiState: StateFlow<OnboardingTourUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            when (val state = onboardingRepository.getState()) {
                is ApiResult.Success -> {
                    if (state.data.done) {
                        _uiState.update { it.copy(isLoading = false, finished = true) }
                        return@launch
                    }
                }
                // On any error, skip the tour rather than block first run.
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                    return@launch
                }
            }
            when (val flow = onboardingRepository.getFlow(surface = "phone")) {
                is ApiResult.Success -> applyFlow(flow.data)
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                }
            }
        }
    }

    private fun applyFlow(flow: OnboardingFlow) {
        val steps = flow.steps.filter { it.kind in KNOWN_KINDS }
        if (steps.isEmpty()) {
            // Nothing renderable: mark complete so we never loop.
            viewModelScope.launch { onboardingRepository.complete(flow.tourId, null) }
            _uiState.update { it.copy(isLoading = false, finished = true) }
            return
        }
        _uiState.update {
            it.copy(isLoading = false, steps = steps, tourId = flow.tourId, currentIndex = 0)
        }
    }

    fun onAdvance() {
        val current = _uiState.value
        val next = current.currentIndex + 1
        if (next >= current.steps.size) {
            finish(skipped = false)
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

    fun onSkip() = finish(skipped = true)

    fun onFinish() = finish(skipped = false)

    private fun finish(skipped: Boolean) {
        val current = _uiState.value
        viewModelScope.launch {
            val lastStep = current.steps.getOrNull(current.currentIndex)?.id
            if (skipped) {
                onboardingRepository.skip(current.tourId, lastStep)
            } else {
                onboardingRepository.complete(current.tourId, lastStep)
            }
        }
        _uiState.update { it.copy(finished = true) }
    }

    /**
     * Writes one setting_choice value. UpdateProfileRequest is typed per
     * field, so the manifest's string key maps onto the matching field;
     * unknown keys (a newer server) are ignored rather than failing the
     * tour. Only profile_field targets exist for phones today.
     */
    fun onSettingChosen(step: OnboardingStep, value: String) {
        val spec = step.setting ?: return
        if (spec.target != "profile_field") return
        val request = when (spec.key) {
            "quality_preference" -> UpdateProfileRequest(qualityPreference = value)
            "subtitle_language" -> UpdateProfileRequest(subtitleLanguage = value)
            "subtitle_mode" -> UpdateProfileRequest(subtitleMode = value)
            "auto_skip_intro" -> UpdateProfileRequest(autoSkipIntro = value.toBoolean())
            "auto_skip_credits" -> UpdateProfileRequest(autoSkipCredits = value.toBoolean())
            else -> return
        }
        viewModelScope.launch {
            profileRepository.updateActiveProfile(request)
        }
    }
}
