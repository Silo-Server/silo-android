package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class PersonRefreshV2Test {
    private var owner=AuthScopeSnapshot("s","p","https://example.invalid","pin",identityGeneration=1)
    private val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope()=owner}
    @Test fun exactString202AndSingleAttemptNoBody()=runTest {
        val c=HttpClient(MockEngine {
            assertEquals("/api/v2/catalog/people/9007199254740993/refresh",it.url.encodedPath)
            assertTrue(it.attributes[SingleAttemptAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertEquals(owner,it.attributes[AuthScopeAttributeKey]); assertEquals(0,it.body.contentLength ?: 0)
            respond("""{"status":"queued","person_id":"9007199254740993"}""",HttpStatusCode.Accepted,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try { assertIs<ApiResult.Success<Unit>>(PersonRefreshV2Api(c, tokens, ApiV2Gate.Unrestricted).refresh(9007199254740993,owner)) } finally {c.close()}
    }
    @Test fun rejectsWrongStatusNumericForeignAndMissingReceipts()=runTest {
        var body="{}"; var status=HttpStatusCode.Accepted
        val c=HttpClient(MockEngine {respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val api=PersonRefreshV2Api(c, tokens, ApiV2Gate.Unrestricted)
            for(b in listOf("{}","""{"status":"queued","person_id":7}""","""{"status":"queued","person_id":"8"}""","""{"status":"done","person_id":"7"}""")) {
                body=b; assertFalse(api.refresh(7,owner) is ApiResult.Success)
            }
            body="""{"status":"queued","person_id":"7"}"""; status=HttpStatusCode.OK
            assertFalse(api.refresh(7,owner) is ApiResult.Success)
        } finally {c.close()}
    }
    @Test fun refusalIsSingleSendAndStalePinBlocksReadsAndReceipts()=runTest {
        var sends=0; var late=false
        val c=HttpClient(MockEngine {
            sends++; if(late) owner=owner.copy(profileToken="new")
            respond("""{"status":"queued","person_id":"7"}""",if(late) HttpStatusCode.Accepted else HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api=PersonRefreshV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.refresh(7,owner)); assertEquals(1,sends)
            val old=owner; late=true
            assertIs<ApiResult.Error>(api.refresh(7,old)); assertIs<ApiResult.Error>(api.detail(7,old)); assertEquals(2,sends)
        } finally {c.close()}
    }
    @Test fun detailRequiresExactStringPersonAndAuthorized200()=runTest {
        var body="""{"id":"7","name":"Person"}"""
        val c=HttpClient(MockEngine {
            assertEquals(owner,it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api=PersonRefreshV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<*>>(api.detail(7,owner))
            body=body.replace("\"7\"","\"8\""); assertFalse(api.detail(7,owner) is ApiResult.Success)
            body=body.replace("\"8\"","7"); assertFalse(api.detail(7,owner) is ApiResult.Success)
        } finally {c.close()}
    }
}
