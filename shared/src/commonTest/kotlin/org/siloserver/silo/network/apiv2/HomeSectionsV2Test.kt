package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.SectionRepository
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class HomeSectionsV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private var captureHook: suspend () -> Unit = {}
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot { val value = owner; captureHook(); return value }
    }
    private val good = """{"sections":[{"id":"second","section_type":"continue_watching","title":"Second","items":[{"content_id":"movie:2","type":"movie","title":"Two"}]},{"id":"first","section_type":"next_up","title":"First","items":[]}]}"""
    @Test fun scopedOrderedEmptyAndMalformedReads() = runTest {
        var body = good; var status = HttpStatusCode.OK; var late = false; var sends = 0
        val client = HttpClient(MockEngine {
            sends++; assertEquals("/api/v2/home/sections", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            if (late) owner = owner.copy(profileToken = "new")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted); val original = owner
            assertEquals(listOf("second", "first"), api.list(owner).getOrThrow().sections.map { it.id })
            for (bad in listOf("{}", good.replace("\"items\"", "\"missing\""), good.replace("\"movie:2\"", "\"\""))) {
                body = bad; assertFalse(api.list(owner) is ApiResult.Success)
            }
            body = good; status = HttpStatusCode.Accepted; assertFalse(api.list(owner) is ApiResult.Success)
            status = HttpStatusCode.OK; body = """{"sections":[]}"""; assertTrue(api.list(owner).getOrThrow().sections.isEmpty())
            late = true; assertIs<ApiResult.Error>(api.list(original))
            val sent = sends; assertIs<ApiResult.Error>(api.list(original)); assertEquals(sent, sends)
        } finally { client.close() }
    }
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatches++; Dispatchers.Default.dispatch(context, block) }
    }
    // With 100-item rows Home is over 1 MB; reading it on the caller's main thread stalled low-end TVs.
    @Test fun homeResponsesAreReadAndDecodedOnTheDecodeDispatcher() = runTest {
        val row = """{"id":"second","section_type":"continue_watching","title":"Second","items":[{"content_id":"movie:2","type":"movie","title":"Two"}]}"""
        val client = HttpClient(MockEngine {
            respond(if (it.url.encodedPath.endsWith("/items")) row else good, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val decode = RecordingDispatcher()
            val api = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted, decodeDispatcher = decode)
            api.list(owner).getOrThrow(); val afterList = decode.dispatches; assertTrue(afterList > 0)
            assertEquals("second", api.section("second", owner).getOrThrow().section?.id); assertTrue(decode.dispatches > afterList)
        } finally { client.close() }
    }
    @Test fun firstLoadJoinsARunningReadButRefreshesNeverDo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var sends = 0; var gate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { sends++; gate.await(); respond(good, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        }))
        try {
            val repository = SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted, decodeDispatcher = dispatcher)))
            val warmup = async { repository.getHomeSections(owner) }; runCurrent()
            val firstLoad = async { repository.getHomeSectionsJoiningRead(owner) }; runCurrent(); assertEquals(1, sends)
            val refresh = async { repository.getHomeSections(owner) }; runCurrent(); assertEquals(2, sends)
            gate.complete(Unit)
            for (read in listOf(warmup, firstLoad, refresh)) assertEquals(listOf("second", "first"), read.await().getOrThrow().sections.map { it.id })
            assertEquals(2, sends)
            // A joined read cancelled with its own caller leaves the first load to read for itself.
            gate = CompletableDeferred()
            val cancelled = async { repository.getHomeSections(owner) }; runCurrent()
            val retried = async { repository.getHomeSectionsJoiningRead(owner) }; runCurrent(); assertEquals(3, sends)
            cancelled.cancel(); runCurrent(); assertEquals(4, sends)
            gate.complete(Unit); assertIs<ApiResult.Success<*>>(retried.await())
            // Nothing running: the first load reads normally.
            assertIs<ApiResult.Success<*>>(repository.getHomeSectionsJoiningRead(owner)); assertEquals(5, sends)
        } finally { client.close() }
    }
    @Test fun suspendedAuthorityCannotPublishIntoReplacementPlaybackRun() = runTest {
        var sends = 0; var checks = 0; var run = 1; var published = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { sends++; respond(good, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val repository = SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            captureHook = { if (sends == 1 && ++checks == 1) { entered.complete(Unit); release.await() } }
            val task = async { repository.loadScopedHomeSections(owner, { run == 1 }) { published = true } }
            entered.await(); run = 2; release.complete(Unit); task.await()
            assertFalse(published); assertEquals(1, sends)
            captureHook = {}; repository.loadScopedHomeSections(owner, { false }) { published = true }
            assertFalse(published); assertEquals(1, sends)
        } finally { client.close() }
    }
}
