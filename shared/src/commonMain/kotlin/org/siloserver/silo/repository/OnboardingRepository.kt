package org.siloserver.silo.repository

import org.siloserver.silo.model.onboarding.OnboardingFlow
import org.siloserver.silo.model.onboarding.OnboardingProgressRequest
import org.siloserver.silo.model.onboarding.OnboardingState
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OnboardingApi

/**
 * First-run tour state and manifest. Completion is per profile and lives on
 * the server, so finishing on any device silences every other one.
 */
class OnboardingRepository(private val api: OnboardingApi) {

    suspend fun getFlow(surface: String, scope: AuthScopeSnapshot): ApiResult<OnboardingFlow> = api.getFlow(surface, scope)

    suspend fun getState(scope: AuthScopeSnapshot): ApiResult<OnboardingState> = api.getState(scope)

    suspend fun recordStep(tourId: String, stepId: String, scope: AuthScopeSnapshot): ApiResult<Unit> =
        api.putProgress(OnboardingProgressRequest(tourId = tourId, lastStep = stepId), scope)

    suspend fun complete(tourId: String, lastStep: String?, scope: AuthScopeSnapshot): ApiResult<Unit> =
        api.putProgress(
            OnboardingProgressRequest(tourId = tourId, lastStep = lastStep, completed = true), scope,
        )

    suspend fun skip(tourId: String, lastStep: String?, scope: AuthScopeSnapshot): ApiResult<Unit> =
        api.putProgress(
            OnboardingProgressRequest(tourId = tourId, lastStep = lastStep, skipped = true), scope,
        )
}
