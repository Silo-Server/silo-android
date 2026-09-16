package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.RecommendationApi
import org.siloserver.silo.repository.RecommendationRepository
import kotlin.test.*

class SimilarCardsV2Test {
    private var owner=AuthScopeSnapshot("s","p","https://example.invalid","pin",identityGeneration=1)
    private var onCapture:suspend ()->Unit={}
    private val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope(): AuthScopeSnapshot {
        val captured=owner; onCapture(); return captured
    }}
    private val cards="""{"items":[{"content_id":"b","title":"Second","type":"movie","year":2024,"poster_url":"/b"},{"content_id":"a","title":"First","type":"series"}]}"""
    @Test fun orderedCardsOneRequestNoDetailHydrationAndEmptyCollection()=runTest {
        var sends=0; var body=cards
        val c=HttpClient(MockEngine {
            sends++;assertEquals("/api/v2/recommendations/similar/movie%2Fa%3Fb",it.url.encodedPath)
            assertEquals("12",it.url.parameters["limit"]);assertEquals(owner,it.attributes[AuthScopeAttributeKey]);assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val repo=RecommendationRepository(RecommendationApi(c,SimilarCardsV2Api(c, tokens, ApiV2Gate.Unrestricted)))
            val published=mutableListOf<List<String>>()
            repo.loadSimilarCards("movie/a?b",owner,{true}) {published+=it.map {it.contentId};assertEquals(2024,it.first().year);assertEquals("/b",it.first().posterUrl)}
            assertEquals(listOf(listOf("b","a")),published);assertEquals(1,sends)
            body="""{"items":[]}"""
            repo.loadSimilarCards("movie/a?b",owner,{true}) {assertTrue(it.isEmpty())};assertEquals(2,sends)
        } finally {c.close()}
    }
    @Test fun rejectsIncompleteDuplicateBlankOversizePagedAndWrongStatus()=runTest {
        var body=cards;var status=HttpStatusCode.OK
        val c=HttpClient(MockEngine {respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val api=SimilarCardsV2Api(c, tokens, ApiV2Gate.Unrestricted)
            for(b in listOf(cards.dropLast(1)+""","page":{"has_more":true,"next_cursor":"c"}}""",cards.replace("\"a\"","\"b\""),cards.replace("\"b\"","\"\""))) {
                body=b;assertFalse(api.list("movie",12,owner) is ApiResult.Success)
            }
            body=cards;assertFalse(api.list("movie",1,owner) is ApiResult.Success)
            status=HttpStatusCode.Accepted;assertFalse(api.list("movie",12,owner) is ApiResult.Success)
        } finally {c.close()}
    }
    @Test fun replacementPinBeforeDispatchAndAfterReplyCannotPublish()=runTest {
        var sends=0
        val c=HttpClient(MockEngine {sends++;owner=owner.copy(profileToken="new");respond(cards,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val repo=RecommendationRepository(RecommendationApi(c,SimilarCardsV2Api(c, tokens, ApiV2Gate.Unrestricted)))
            val original=owner
            repo.loadSimilarCards("movie",original,{true}) {fail("late owner published")}
            repo.loadSimilarCards("movie",original,{true}) {fail("replacement dispatched")}
            assertEquals(1,sends)
        } finally {c.close()}
    }
    @Test fun suspendedAuthorityLookupMustRecheckRunBeforePublication()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var run=1;var sent=false;var captureCount=0
        val c=HttpClient(MockEngine {sent=true;respond(cards,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val repo=RecommendationRepository(RecommendationApi(c,SimilarCardsV2Api(c, tokens, ApiV2Gate.Unrestricted)))
            onCapture={ if(sent && ++captureCount==2) {entered.complete(Unit);release.await()} }
            val job=launch {repo.loadSimilarCards("movie",owner,{run==1}) {fail("old run published")}}
            entered.await();run=2;release.complete(Unit);job.join()
        } finally {c.close()}
    }
    @Test fun cancelledOrSupersededRequestDoesNotPublish()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var sends=0
        val c=HttpClient(MockEngine {sends++;entered.complete(Unit);release.await();respond(cards,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val repo=RecommendationRepository(RecommendationApi(c,SimilarCardsV2Api(c, tokens, ApiV2Gate.Unrestricted)))
            repo.loadSimilarCards("movie",owner,{false}) {fail("superseded published")};assertEquals(0,sends)
            val job=launch{repo.loadSimilarCards("movie",owner,{true}) {fail("cancelled published")}}
            entered.await();job.cancelAndJoin();release.complete(Unit);assertEquals(1,sends)
        } finally {c.close()}
    }
}
