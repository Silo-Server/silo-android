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
import org.siloserver.silo.domain.player.IntroSkipMode
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
            val resumeStep: String?
            when (val state = stateDeferred.await()) {
                is ApiResult.Success -> {
                    if (state.data.done) {
                        // Server-confirmed done — safe to cache locally.
                        markDoneLocally()
                        flowDeferred.cancel()
                        _uiState.update { it.copy(isLoading = false, finished = true) }
                        return@launch
                    }
                    resumeStep = state.data.lastStep
                }
                // On any error, skip the tour rather than block first run —
                // but don't cache done: the server was never consulted.
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    flowDeferred.cancel()
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                    return@launch
                }
            }
            when (val flow = flowDeferred.await()) {
                is ApiResult.Success -> applyFlow(flow.data, resumeStep)
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoading = false, finished = true) }
                }
            }
        }
    }

    private suspend fun applyFlow(flow: OnboardingFlow, resumeStep: String?) {
        val steps = flow.steps.filter { it.kind in KNOWN_KINDS }
        if (steps.isEmpty()) {
            // Nothing renderable: mark complete so we never loop — locally
            // only once the server acknowledged, so an offline auto-complete
            // is retried next launch instead of silently diverging.
            _uiState.update { it.copy(isLoading = false, finished = true) }
            // Snapshot before the POST: finished=true navigates to Home, where
            // the active profile can change while this is still in flight.
            val serverId = tokenManager.getCurrentServerId()
            val profileId = tokenManager.getProfileId()
            withContext(NonCancellable) {
                if (onboardingRepository.complete(flow.tourId, null) is ApiResult.Success) {
                    localCache.markDone(serverId, profileId)
                }
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
        // Progress recorded from another device or before a process death:
        // resume at the server's last-seen step instead of step one.
        val startIndex = resumeStep
            ?.let { last -> steps.indexOfFirst { it.id == last } }
            ?.takeIf { it >= 0 }
            ?: 0
        _uiState.update {
            it.copy(
                isLoading = false,
                steps = steps,
                tourId = flow.tourId,
                currentIndex = startIndex,
                pendingChoices = defaults,
            )
        }
    }

    fun onAdvance() {
        val current = _uiState.value
        val next = current.currentIndex + 1
        if (next >= current.steps.size) {
            finish(skipped = false, persistCurrentChoice = true)
            return
        }
        moveTo(next)
    }

    fun onBack() {
        moveTo(_uiState.value.currentIndex - 1)
    }

    /**
     * Settle handler for swipe navigation. The pager is the one that moved, so
     * this only reconciles state; the screen must not echo it back as a scroll
     * or the two chase each other.
     */
    fun onPageSettled(index: Int) {
        if (index == _uiState.value.currentIndex) return
        moveTo(index)
    }

    /**
     * Single path for every index change — button or swipe — so a step reached
     * by swiping records and persists exactly like one reached by tapping.
     */
    private fun moveTo(target: Int) {
        val current = _uiState.value
        val index = target.coerceIn(0, current.steps.lastIndex.coerceAtLeast(0))
        if (index == current.currentIndex) return
        // Advancing past a setting_choice commits it; going back doesn't, so a
        // user who swipes backwards to reconsider isn't saving on the way out.
        if (index > current.currentIndex) {
            persistChoiceIfAny(current.steps.getOrNull(current.currentIndex))
            viewModelScope.launch {
                current.steps.getOrNull(index)?.let {
                    onboardingRepository.recordStep(current.tourId, it.id)
                }
            }
        }
        _uiState.update { it.copy(currentIndex = index) }
    }

    fun onSkip() = finish(skipped = true, persistCurrentChoice = false)

    fun onFinish() = finish(skipped = false, persistCurrentChoice = true)

    private fun finish(skipped: Boolean, persistCurrentChoice: Boolean) {
        val current = _uiState.value
        if (persistCurrentChoice) {
            persistChoiceIfAny(current.steps.getOrNull(current.currentIndex))
        }
        // Skip tapped while the manifest is still loading: there is no tour
        // id to post against. Let the user through; server state stays
        // not-done, so the tour is simply offered again another time.
        if (current.tourId.isBlank()) {
            _uiState.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            val lastStep = current.steps.getOrNull(current.currentIndex)?.id
            // Snapshot the identity that is finishing the tour. finished=true
            // navigates to Home, where the user can switch profile or server
            // while this POST is still in flight — reading the token manager on
            // acknowledgement would then mark whichever profile is active by
            // then, letting it skip a tour it never saw.
            val serverId = tokenManager.getCurrentServerId()
            val profileId = tokenManager.getProfileId()
            // finished=true (below) navigates away with popUpTo, which clears
            // this ViewModel and cancels its scope — the POST must survive
            // that or the server never learns the tour ended and re-shows it.
            // The local done-cache is written only on the server's ack: if
            // the POST is lost, the next launch re-consults the server and
            // retries the tour rather than silently diverging from every
            // other client.
            withContext(NonCancellable) {
                val result = if (skipped) {
                    onboardingRepository.skip(current.tourId, lastStep)
                } else {
                    onboardingRepository.complete(current.tourId, lastStep)
                }
                if (result is ApiResult.Success) {
                    localCache.markDone(serverId, profileId)
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
                // Android playback and the Settings screen read quality and
                // auto-skip from the local player settings store, not the
                // profile fields — mirror those there too or the choice the
                // tour just showed has no visible effect in this app.
                when (spec.key) {
                    "quality_preference" -> playerSettingsStore.setPreferredQuality(value)
                    // The tour's step is still the profile DTO's boolean, but
                    // what this device plays back with is the enum that
                    // superseded it, so the mirror writes the mode. `never` is
                    // not reachable from the tour; the settings screen offers it.
                    "auto_skip_intro" -> playerSettingsStore.setIntroSkipMode(
                        IntroSkipMode.fromLegacyBoolean(value.toBoolean()),
                    )
                    "auto_skip_credits" -> playerSettingsStore.setAutoSkipCredits(value.toBoolean())
                }
                // Best-effort against the profile: the local store above is
                // what this device plays back with, and Settings re-syncs
                // from the server later. A rejected PUT here shouldn't trap
                // the user in a tour they can't leave.
                profileRepository.updateActiveProfile(request)
            }
        }
    }
}
