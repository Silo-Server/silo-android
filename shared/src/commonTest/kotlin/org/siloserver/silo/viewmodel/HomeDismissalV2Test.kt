package org.siloserver.silo.viewmodel

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.repository.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDismissalV2Test {
    @Test fun observedProgressIsSentOnceAndRemovedOnlyAfter204() = scenario("success")
    @Test fun nextUpPreservesSeriesAnchorAndEncodedItem() = scenario("next")
    @Test fun wrongSuccessStatusKeepsCard() = scenario("wrong_status")
    @Test fun serverFailureKeepsCard() = scenario("failure")
    @Test fun changedPinBeforeDispatchSendsNothing() = scenario("before")
    @Test fun changedPinDuringPutCannotPublishReceipt() = scenario("late")
    @Test fun newerHomeObservationSurvivesOldAcknowledgement() = scenario("refresh")
    @Test fun missingObservedAnchorSendsNothing() = scenario("anchor")
    @Test fun concurrentDifferentCardsEachApplyTheirOwnReceipt() = scenario("concurrent")

    private fun scenario(stage: String) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var owner = AuthScopeSnapshot("server", "profile", "https://silo.test", "pin", identityGeneration = 1)
        val original = owner
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
        val stamp = "2026-09-06T01:02:03.456Z"
        var currentStamp = stamp
        var title = "Original"
        var sends = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                if (request.method == HttpMethod.Put) {
                    sends++
                    val surface = if (stage == "next") "next_up" else "continue_watching"
                    assertTrue(request.url.encodedPath in listOfNotNull("/api/v2/home/dismissals/$surface/movie%2Fa",
                        "/api/v2/home/dismissals/$surface/movie%2Fb".takeIf { stage == "concurrent" }))
                    assertEquals(original, request.attributes[AuthScopeAttributeKey])
                    assertTrue(request.attributes[RequireSiloAuthAttributeKey])
                    val body = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(setOf(if (stage == "next") "series_id" else "progress_updated_at"), body.keys)
                    assertEquals(if (stage == "next") "series:a" else stamp, body.values.single().jsonPrimitive.content)
                    entered.complete(Unit); release.await()
                    val status = when (stage) { "wrong_status" -> HttpStatusCode.OK; "failure" -> HttpStatusCode.ServiceUnavailable; else -> HttpStatusCode.NoContent }
                    respond(if (status == HttpStatusCode.NoContent) "" else "{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    assertEquals("/api/v2/home/sections", request.url.encodedPath)
                    val second = if (stage == "concurrent") """,{"content_id":"movie/b","type":"movie","title":"Second","progress_updated_at":"$currentStamp"}""" else ""
                    respond("""{"sections":[{"id":"row","section_type":"continue_watching","title":"Row","items":[{"content_id":"movie/a","type":"episode","title":"$title","series_id":"series:a","progress_updated_at":"$currentStamp"}$second]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val repository = SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            val vm = HomeViewModel(repository, MediaActionsCoordinator(PersonalDataRepository(PersonalDataApi(client))))
            store.put("home", vm)
            runCurrent()
            assertEquals("Original", vm.uiState.value.sections.single().items.first().title)
            fun dismiss() {
                if (stage == "next") vm.dismissNextUp("movie/a", "series:a")
                else vm.dismissContinueWatching("movie/a", if (stage == "anchor") "not-observed" else stamp)
            }
            dismiss(); dismiss()
            if (stage == "concurrent") vm.dismissContinueWatching("movie/b", stamp)
            if (stage == "before") owner = original.copy(profileToken = "new")
            runCurrent()
            if (stage in listOf("before", "anchor")) {
                assertEquals(0, sends)
            } else {
                entered.await()
                assertEquals(if (stage == "concurrent") 2 else 1, sends)
                assertEquals("Original", vm.uiState.value.sections.single().items.first().title)
                if (stage == "late") owner = original.copy(profileToken = "new")
                if (stage == "refresh") {
                    title = "New observation"; currentStamp = "2026-09-06T02:03:04Z"
                    vm.refresh(); runCurrent()
                }
                release.complete(Unit); runCurrent()
                when (stage) {
                    "success", "next", "late", "concurrent" -> assertTrue(vm.uiState.value.sections.isEmpty())
                    "refresh" -> assertEquals("New observation", vm.uiState.value.sections.single().items.first().title)
                    else -> assertEquals("Original", vm.uiState.value.sections.single().items.first().title)
                }
            }
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }
}
