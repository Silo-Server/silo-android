package org.siloserver.silo.metadata

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.siloserver.silo.model.metadata.MetadataAiStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.MetadataAiApi
import org.siloserver.silo.repository.MetadataAiRepository

class DescriptionTranslationControllerTest {

    @Test
    fun translatePollsUntilPendingLanguageClearsThenSignalsRefresh() = runTest {
        val api = FakeApi(translateResult = ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", "movie-1", "nl", "pending")))
        // Pending language stays set for two polls, then clears.
        var polls = 0
        var refreshed = false
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )

        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = {
                polls += 1
                if (polls < 3) "nl" else null
            },
            onTranslated = { refreshed = true },
        )

        assertEquals(DescriptionTranslationPhase.Idle, controller.phase.value)
        assertEquals(3, polls)
        assertTrue(refreshed)
    }

    @Test
    fun translateFailureSurfacesFailedPhase() = runTest {
        val api = FakeApi(
            translateResult = ApiResult.Error(code = 503, error = "not_configured", message = "not configured"),
        )
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )

        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = { "nl" },
            onTranslated = { },
        )

        assertEquals(DescriptionTranslationPhase.Failed, controller.phase.value)
    }

    @Test
    fun pollBudgetExhaustionFails() = runTest {
        val api = FakeApi(translateResult = ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", "movie-1", "nl", "pending")))
        var polls = 0
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )

        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = {
                polls += 1
                "nl" // never clears
            },
            onTranslated = { },
        )

        assertEquals(DescriptionTranslationPhase.Failed, controller.phase.value)
        // Apple's bounded backoff schedule has 8 slots.
        assertEquals(8, polls)
    }

    @Test
    fun throwingRefetchFailsInsteadOfStrandingTranslatingPhase() = runTest {
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(FakeApi(ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", "movie-1", "nl", "pending")))),
            delayMs = { },
        )

        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = { throw IllegalStateException("detail fetch blew up") },
            onTranslated = { },
        )

        // A stuck Translating phase would block every future translate()
        // call on this controller; failures must land on Failed.
        assertEquals(DescriptionTranslationPhase.Failed, controller.phase.value)
    }

    @Test
    fun autoFireLatchesPerContentAndLanguage() {
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(FakeApi(ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", "movie-1", "nl", "pending")))),
            delayMs = { },
        )

        assertTrue(controller.shouldAutoFire("movie-1", "nl"))
        controller.markAutoFired("movie-1", "nl")
        assertEquals(false, controller.shouldAutoFire("movie-1", "nl"))
        assertTrue(controller.shouldAutoFire("movie-1", "de"))
        assertTrue(controller.shouldAutoFire("movie-2", "nl"))
    }
}

private class FakeApi(
    private val translateResult: ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob>,
) : MetadataAiApi {
    override suspend fun status(): ApiResult<MetadataAiStatus> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun translateDescription(
        contentId: String,
        targetLanguage: String,
        scope: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob> = translateResult
}
