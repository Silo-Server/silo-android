// shared/src/commonTest/kotlin/org/siloserver/silo/repository/SubtitlesRepositoryTest.kt
package org.siloserver.silo.repository

import org.siloserver.silo.model.subtitles.DownloadedSubtitlesResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJob
import org.siloserver.silo.model.subtitles.SubtitleAiJobResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobsResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobStatus
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleDownloadResponse
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleSearchResponse
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.SubtitlesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitlesRepositoryTest {

    private fun job(
        status: String,
        progress: Double = 0.0,
        resultSubtitleId: Int? = null,
        errorMessage: String? = null,
    ) = SubtitleAiJob(
        id = 91L,
        mediaFileId = 1048,
        kind = "translate",
        sourceIndex = 2,
        sourceLanguage = "en",
        targetLanguage = "nl",
        engine = "openai",
        model = "gpt-4o-mini",
        status = status,
        progress = progress,
        progressMessage = "",
        resultSubtitleId = resultSubtitleId,
        errorMessage = errorMessage,
        createdAt = "2026-06-12T10:00:00Z",
        updatedAt = "2026-06-12T10:00:00Z",
    )

    /**
     * Scripted fake: getJob consumes [jobResults] in order and keeps
     * returning the last entry once exhausted (a terminal job stays terminal).
     */
    private class FakeSubtitlesApi(
        val jobResults: MutableList<ApiResult<SubtitleAiJobResponse>> = mutableListOf(),
    ) : SubtitlesApi {
        val calls = mutableListOf<String>()
        var getJobCount = 0

        var searchResult: ApiResult<SubtitleSearchResponse> =
            ApiResult.Success(SubtitleSearchResponse())
        var downloadResult: ApiResult<SubtitleDownloadResponse> =
            ApiResult.Error(code = 500, error = "unset", message = "unset")
        var listResult: ApiResult<DownloadedSubtitlesResponse> =
            ApiResult.Success(DownloadedSubtitlesResponse())
        var aiStatusResult: ApiResult<SubtitleAiStatus> =
            ApiResult.Success(SubtitleAiStatus(enabled = true, transcribeEnabled = true))
        var aiQuotaResult: ApiResult<SubtitleAiQuota> =
            ApiResult.Success(SubtitleAiQuota())
        var translateResult: ApiResult<SubtitleAiJobResponse> =
            ApiResult.Error(code = 500, error = "unset", message = "unset")
        var listJobsResult: ApiResult<SubtitleAiJobsResponse> =
            ApiResult.Success(SubtitleAiJobsResponse())
        var cancelJobResult: ApiResult<Unit> = ApiResult.Success(Unit)

        override suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse> {
            calls += "search:${request.mediaFileId}:${request.languages.joinToString(",")}"
            return searchResult
        }
        override suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse> {
            calls += "download:${request.provider}:${request.subtitleId}"
            return downloadResult
        }
        override suspend fun list(mediaFileId: Int): ApiResult<DownloadedSubtitlesResponse> {
            calls += "list:$mediaFileId"
            return listResult
        }
        override suspend fun aiStatus(): ApiResult<SubtitleAiStatus> {
            calls += "aiStatus"
            return aiStatusResult
        }
        override suspend fun aiQuota(): ApiResult<SubtitleAiQuota> {
            calls += "aiQuota"
            return aiQuotaResult
        }
        override suspend fun translate(request: SubtitleTranslateRequest, scope: AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> {
            calls += "translate:${request.mediaFileId}:${request.kind}:${request.sourceIndex}"
            return translateResult
        }
        override suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> {
            calls += "listJobs:$mediaFileId"
            return listJobsResult
        }
        override suspend fun getJob(jobId: Long, scope: AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> {
            calls += "getJob:$jobId"
            getJobCount++
            return if (jobResults.size > 1) jobResults.removeAt(0) else jobResults.first()
        }
        override suspend fun cancelJob(jobId: Long, scope: AuthScopeSnapshot?): ApiResult<Unit> {
            calls += "cancelJob:$jobId"
            return cancelJobResult
        }
    }

    @Test
    fun `pass-through methods delegate to the api unchanged`() = runTest {
        val api = FakeSubtitlesApi()
        api.listResult = ApiResult.Success(
            DownloadedSubtitlesResponse(
                subtitles = listOf(
                    DownloadedSubtitle(
                        id = 312, mediaFileId = 1048, provider = "opensubtitles",
                        language = "en", format = "srt", releaseName = "R",
                    ),
                ),
            ),
        )
        val repository = SubtitlesRepository(api)

        val search = repository.search(SubtitleSearchRequest(mediaFileId = 1048, languages = listOf("en")))
        val list = repository.list(mediaFileId = 1048)
        val status = repository.aiStatus()
        val quota = repository.aiQuota()
        val jobs = repository.listJobs(mediaFileId = 1048)
        val cancel = repository.cancelJob(jobId = 91L)

        assertEquals(api.searchResult, search)
        assertEquals(api.listResult, list)
        assertEquals(api.aiStatusResult, status)
        assertEquals(api.aiQuotaResult, quota)
        assertEquals(api.listJobsResult, jobs)
        assertEquals(ApiResult.Success(Unit), cancel)
        assertEquals(
            listOf("search:1048:en", "list:1048", "aiStatus", "aiQuota", "listJobs:1048", "cancelJob:91"),
            api.calls,
        )
    }

    @Test
    fun `propagates api errors unchanged`() = runTest {
        val api = FakeSubtitlesApi()
        val error = ApiResult.Error(code = 500, error = "search_error", message = "Subtitle search failed")
        api.searchResult = error
        val repository = SubtitlesRepository(api)

        assertEquals(error, repository.search(SubtitleSearchRequest(1048, listOf("en"))))
    }

    @Test
    fun `pollJob completes after N polls reporting each update`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.Success(SubtitleAiJobResponse(job(SubtitleAiJobStatus.Pending))),
                ApiResult.Success(SubtitleAiJobResponse(job(SubtitleAiJobStatus.Running, progress = 0.5))),
                ApiResult.Success(
                    SubtitleAiJobResponse(
                        job(SubtitleAiJobStatus.Completed, progress = 1.0, resultSubtitleId = 313),
                    ),
                ),
            ),
        )
        val repository = SubtitlesRepository(api)
        val updates = mutableListOf<SubtitleAiJob>()

        val outcome = repository.pollJob(jobId = 91L) { updates += it }

        assertEquals(SubtitlesRepository.SubtitleJobOutcome.Completed(resultSubtitleId = 313), outcome)
        assertEquals(3, api.getJobCount)
        assertEquals(
            listOf(SubtitleAiJobStatus.Pending, SubtitleAiJobStatus.Running, SubtitleAiJobStatus.Completed),
            updates.map { it.status },
        )
        // Two non-terminal polls → two 1000 ms virtual-time delays.
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `pollJob failure carries the job error message`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.Success(
                    SubtitleAiJobResponse(
                        job(SubtitleAiJobStatus.Failed, errorMessage = "audio extraction failed"),
                    ),
                ),
            ),
        )
        val repository = SubtitlesRepository(api)

        val outcome = repository.pollJob(jobId = 91L)

        assertEquals(
            SubtitlesRepository.SubtitleJobOutcome.Failed(message = "audio extraction failed"),
            outcome,
        )
    }

    @Test
    fun `pollJob retries through transient errors then succeeds`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.NetworkError(RuntimeException("offline")),
                ApiResult.Error(code = 500, error = "internal_error", message = "blip"),
                ApiResult.Success(
                    SubtitleAiJobResponse(
                        job(SubtitleAiJobStatus.Completed, progress = 1.0, resultSubtitleId = 313),
                    ),
                ),
            ),
        )
        val repository = SubtitlesRepository(api)

        val outcome = repository.pollJob(jobId = 91L)

        assertEquals(SubtitlesRepository.SubtitleJobOutcome.Completed(resultSubtitleId = 313), outcome)
        assertEquals(3, api.getJobCount)
        assertEquals(2_000L, currentTime)  // each transient error waited one interval
    }

    @Test
    fun `pollJob treats 404 as terminal failure`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.Error(code = 404, error = "not_found", message = "Job not found"),
            ),
        )
        val repository = SubtitlesRepository(api)

        val outcome = repository.pollJob(jobId = 91L)

        assertEquals(
            SubtitlesRepository.SubtitleJobOutcome.Failed(message = "This job no longer exists on the server."),
            outcome,
        )
        assertEquals(1, api.getJobCount)
    }

    @Test
    fun `pollJob maps cancelled status to Cancelled outcome`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.Success(SubtitleAiJobResponse(job(SubtitleAiJobStatus.Pending))),
                ApiResult.Success(SubtitleAiJobResponse(job(SubtitleAiJobStatus.Cancelled))),
            ),
        )
        val repository = SubtitlesRepository(api)

        val outcome = repository.pollJob(jobId = 91L)

        assertEquals(SubtitlesRepository.SubtitleJobOutcome.Cancelled, outcome)
    }

    @Test
    fun `pollJob honors coroutine cancellation`() = runTest {
        val api = FakeSubtitlesApi(
            jobResults = mutableListOf(
                ApiResult.Success(SubtitleAiJobResponse(job(SubtitleAiJobStatus.Pending))),
            ),
        )
        val repository = SubtitlesRepository(api)

        val polling = launch { repository.pollJob(jobId = 91L) }
        testScheduler.advanceTimeBy(3_500L)
        runCurrent()
        val pollsBeforeCancel = api.getJobCount
        assertTrue(pollsBeforeCancel >= 3)

        polling.cancel()
        polling.join()
        testScheduler.advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(polling.isCancelled)
        assertEquals(pollsBeforeCancel, api.getJobCount)  // no polls after cancellation
    }
}
