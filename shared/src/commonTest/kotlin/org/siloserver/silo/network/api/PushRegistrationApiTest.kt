package org.siloserver.silo.network.api

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.*
import kotlin.test.*

class PushRegistrationApiTest {
    private var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val request = PushDeviceRegisterRequest("android", "t".repeat(80), "device", "private_push")
    private val key = "k".repeat(43)
    private val receipt = """{"generation":"9007199254740993","registration_id":"opaque:r","server_device_id":"opaque:d","push_mode":"private_push"}"""
    @Test fun exactReceiptAndHeadersSingleSend() = runTest {
        val expected = owner
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/notifications/push/devices", it.url.encodedPath)
            assertEquals(key, it.headers["X-Push-Installation-Key"])
            assertEquals("9007199254740993", it.headers["X-Push-Generation"])
            assertEquals(expected, it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[SingleAttemptAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertFalse(it.url.toString().contains(key))
            respond(receipt, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val r = assertIs<ApiResult.Success<PushDeviceRegisterResponse>>(DefaultPushRegistrationApi(client, tokens, ApiV2Gate.Unrestricted).register(request,key,9007199254740993,expected)).data
            assertEquals("opaque:r",r.id); assertEquals("opaque:d",r.serverDeviceId); assertEquals("9007199254740993",r.generation)
        } finally { client.close() }
    }
    @Test fun rejectsNumericMismatchedMissingAndWrongStatusReceipts() = runTest {
        var body = receipt
        var status = HttpStatusCode.OK
        val client = HttpClient(MockEngine { respond(body,status,headersOf(HttpHeaders.ContentType,"application/json")) }) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        try {
            val api = DefaultPushRegistrationApi(client, tokens, ApiV2Gate.Unrestricted)
            for (bad in listOf("{}", receipt.replace("\"9007199254740993\"","9007199254740993"), receipt.replace("9007199254740993","2"),receipt.replace("private_push","off"),receipt.replace("opaque:r",""))) {
                body=bad; assertIs<ApiResult.Error>(api.register(request,key,9007199254740993,owner))
            }
            body=receipt; status=HttpStatusCode.Accepted
            assertFalse(api.register(request,key,9007199254740993,owner) is ApiResult.Success)
        } finally { client.close() }
    }
    @Test fun removalRequires204AndCapturedPathAndHeaders() = runTest {
        var status = HttpStatusCode.NoContent
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/notifications/push/devices/device%2Fone",it.url.encodedPath)
            assertEquals(HttpMethod.Delete,it.method); assertEquals("1",it.headers["X-Push-Generation"])
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            respond("",status)
        })
        try {
            val api=DefaultPushRegistrationApi(client, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<Unit>>(api.delete("device/one",key,1,owner))
            status=HttpStatusCode.OK; assertFalse(api.delete("device/one",key,1,owner) is ApiResult.Success)
        } finally { client.close() }
    }
    @Test fun refusalIsSingleSendAndLatePinCannotPublish() = runTest {
        var sends=0
        var late=false
        val client=HttpClient(MockEngine {
            sends++; if(late) owner=owner.copy(profileToken="new")
            respond(if(late) receipt else "{}",if(late) HttpStatusCode.OK else HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val api=DefaultPushRegistrationApi(client, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.register(request,key,9007199254740993,owner)); assertEquals(1,sends)
            late=true; val captured=owner
            assertIs<ApiResult.Error>(api.register(request,key,9007199254740993,captured)); assertEquals(2,sends)
            assertIs<ApiResult.Error>(api.register(request,key,9007199254740993,captured)); assertEquals(2,sends)
        } finally { client.close() }
    }
    @Test fun capabilityRequiresOrderedRevisionPlatformAndStorage() = runTest {
        var body="""{"revision":"4bc701e0","state":"available","allowed":true,"registration_available":true,"platforms":["android"]}"""
        val client=HttpClient(MockEngine {
            assertEquals("/api/v2/notifications/push/devices/capabilities",it.url.encodedPath)
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api=DefaultPushRegistrationApi(client, tokens, ApiV2Gate.Unrestricted)
            assertEquals(true,assertIs<ApiResult.Success<Boolean>>(api.available(owner)).data)
            body=body.replace("true","false"); assertEquals(false,assertIs<ApiResult.Success<Boolean>>(api.available(owner)).data)
            body="{}"; assertEquals(false,assertIs<ApiResult.Success<Boolean>>(api.available(owner)).data)
        } finally { client.close() }
    }
}
