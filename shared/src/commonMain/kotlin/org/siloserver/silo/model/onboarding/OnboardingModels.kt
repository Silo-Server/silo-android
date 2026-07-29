package org.siloserver.silo.model.onboarding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven onboarding tour manifest. The server has already filtered
 * out steps for disabled features and for the requested surface; the client
 * renders the step kinds it knows and silently skips the rest — that skip is
 * the forward-compatibility contract.
 */
@Serializable
data class OnboardingFlow(
    val version: Int,
    @SerialName("tour_id") val tourId: String,
    val steps: List<OnboardingStep> = emptyList(),
)

@Serializable
data class OnboardingStep(
    val id: String,
    /** Open string on purpose: unknown kinds must be skipped, not fail decode. */
    val kind: String,
    val title: String? = null,
    val body: String? = null,
    /** Client-side asset key; the server never sends image URLs. */
    val illustration: String? = null,
    val setting: OnboardingSettingSpec? = null,
)

@Serializable
data class OnboardingSettingSpec(
    /** "profile_field" | "setting" | "device_setting" — selects the write API. */
    val target: String,
    val key: String,
    val control: String,
    val options: List<OnboardingSettingOption> = emptyList(),
    val default: String? = null,
    val label: String? = null,
)

@Serializable
data class OnboardingSettingOption(
    val value: String,
    val label: String,
)

@Serializable
data class OnboardingState(
    @SerialName("tour_id") val tourId: String,
    @SerialName("last_step") val lastStep: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("skipped_at") val skippedAt: String? = null,
    val done: Boolean = false,
)

@Serializable
data class OnboardingProgressRequest(
    @SerialName("tour_id") val tourId: String,
    @SerialName("last_step") val lastStep: String? = null,
    val completed: Boolean = false,
    val skipped: Boolean = false,
)
