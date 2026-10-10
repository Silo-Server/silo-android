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
        // Bounded backoff: 11 slots, 45 seconds in all (the web client's budget).
        assertEquals(11, polls)
    }

    @Test
    fun pollBudgetSpansFortyFiveSeconds() = runTest {
        val api = FakeApi(translateResult = ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "season", "season-1", "nl", "pending")))
        var waitedMs = 0L
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { waitedMs += it },
        )

        controller.translate(
            contentId = "season-1",
            targetLanguage = "nl",
            refetchPendingLanguage = { "nl" },
            onTranslated = { },
        )

        assertEquals(45_000L, waitedMs)
    }

    @Test
    fun onPollRunsBeforeEveryRefetchSoDependentListsRefresh() = runTest {
        val api = FakeApi(translateResult = ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "season", "season-1", "nl", "pending")))
        val events = mutableListOf<String>()
        var polls = 0
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )

        controller.translate(
            contentId = "season-1",
            targetLanguage = "nl",
            refetchPendingLanguage = {
                polls += 1
                events += "refetch"
                if (polls < 2) "nl" else null
            },
            onTranslated = { events += "translated" },
            onPoll = { events += "poll" },
        )

        assertEquals(listOf("poll", "refetch", "poll", "refetch", "translated"), events)
    }

    @Test
    fun runningContentIdNamesTheItemBeingTranslated() = runTest {
        val api = FakeApi(translateResult = ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", "movie-1", "nl", "pending")))
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )
        var seenWhilePolling: String? = null
        var phaseWhilePolling: DescriptionTranslationPhase? = null

        assertEquals(null, controller.runningContentId.value)
        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = {
                seenWhilePolling = controller.runningContentId.value
                phaseWhilePolling = controller.phase.value
                null
            },
            onTranslated = { },
        )

        assertEquals("movie-1", seenWhilePolling)
        assertEquals(DescriptionTranslationPhase.Translating, phaseWhilePolling)
        assertEquals(DescriptionTranslationPhase.Idle, controller.phase.value)
    }

    @Test
    fun buttonTriggerIsSingleFlightWhileATranslationRuns() = runTest {
        var translateCalls = 0
        val api = object : MetadataAiApi {
            override suspend fun status(): ApiResult<MetadataAiStatus> =
                ApiResult.NetworkError(IllegalStateException("not used"))

            override suspend fun translateDescription(
                contentId: String,
                targetLanguage: String,
                scope: org.siloserver.silo.network.AuthScopeSnapshot?,
            ): ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob> {
                translateCalls += 1
                return ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "item", contentId, targetLanguage, "pending"))
            }
        }
        val controller = DescriptionTranslationController(
            repository = MetadataAiRepository(api),
            delayMs = { },
        )

        controller.translate(
            contentId = "movie-1",
            targetLanguage = "nl",
            refetchPendingLanguage = {
                // A second press while the first job polls must not queue another.
                controller.translate("movie-1", "nl", refetchPendingLanguage = { null }, onTranslated = { })
                null
            },
            onTranslated = { },
        )

        assertEquals(1, translateCalls)
        // Once idle again, the button can fire a fresh request.
        controller.translate("movie-1", "nl", refetchPendingLanguage = { null }, onTranslated = { })
        assertEquals(2, translateCalls)
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
    fun aSeasonSwitchedToWhileAnotherTranslatesIsDeferredNotLatched() = runTest {
        val started = mutableListOf<String>()
        val api = object : MetadataAiApi {
            override suspend fun status(): ApiResult<MetadataAiStatus> =
                ApiResult.NetworkError(IllegalStateException("not used"))

            override suspend fun translateDescription(
                contentId: String,
                targetLanguage: String,
                scope: org.siloserver.silo.network.AuthScopeSnapshot?,
            ): ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob> {
                started += contentId
                return ApiResult.Success(org.siloserver.silo.model.metadata.MetadataTranslationJob("1", "season", contentId, targetLanguage, "pending"))
            }
        }
        val controller = DescriptionTranslationController(MetadataAiRepository(api), delayMs = { })
        var claimedWhileBusy: Boolean? = null

        // Season 1's job runs; the viewer switches to season 2 mid-poll.
        assertTrue(controller.claimAutoFire("season-1", "de"))
        controller.translate(
            contentId = "season-1",
            targetLanguage = "de",
            refetchPendingLanguage = {
                claimedWhileBusy = controller.claimAutoFire("season-2", "de")
                null
            },
            onTranslated = { },
        )

        assertEquals(false, claimedWhileBusy)
        // The refused claim is reported once, and season 2 was not latched.
        assertTrue(controller.takeDeferredAuto())
        assertEquals(false, controller.takeDeferredAuto())
        assertTrue(controller.claimAutoFire("season-2", "de"))
        controller.translate("season-2", "de", refetchPendingLanguage = { null }, onTranslated = { })
        assertEquals(listOf("season-1", "season-2"), started)
        // Season 1 stays latched for the session.
        assertEquals(false, controller.claimAutoFire("season-1", "de"))
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
