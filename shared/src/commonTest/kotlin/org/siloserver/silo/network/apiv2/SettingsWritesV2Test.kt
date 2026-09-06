package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import kotlin.test.*

class SettingsWritesV2Test {
    private var owner = AuthScopeSnapshot("server", "parent", "https://example.invalid", "proof", identityGeneration = 1, credentialEpoch = 2)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }

    @Test fun targetProfileIsQueryAndActorProofStaysCapturedWithoutReceiptReplay() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/settings/values/ui.theme", it.url.encodedPath)
            assertEquals("child", it.url.parameters["profile_id"])
            assertEquals("profile_library", it.url.parameters["scope"])
            assertEquals("7", it.url.parameters["library_id"])
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            assertNull(it.headers["X-Profile-Id"])
            assertNull(it.headers["X-Silo-Mutation-Id"])
            assertNull(it.headers[HttpHeaders.IfMatch])
            assertFalse(it.attributes.getOrNull(SingleAttemptAttributeKey) == true)
            assertEquals("{\"value\":\"dark\"}", it.body.toByteArray().decodeToString())
            respond("""{"key":"ui.theme","scope":"profile_library","profile_id":"child","library_id":"7","value":"dark","revision":9007199254740993}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val api = SettingsApi(client, writesV2 = SettingsWritesV2Api(client,tokens))
            val value = assertIs<ApiResult.Success<StoredSettingValue>>(api.putValue("ui.theme",SettingScopeIdentity.profileLibrary(7),JsonPrimitive("dark"),"local-id","child",owner)).data
            assertEquals(9007199254740993L,value.revision); assertEquals(7,value.libraryId)
        } finally { client.close() }
    }

    @Test fun deleteRequires204AndLateOrReplacedAuthorityNeverSucceeds() = runTest {
        var sends = 0
        var status = HttpStatusCode.OK
        var change = false
        val original = owner
        val client = HttpClient(MockEngine {
            sends++
            assertEquals(HttpMethod.Delete,it.method)
            assertTrue(it.body.toByteArray().isEmpty())
            if (change) owner = owner.copy(profileToken = "replacement")
            respond("",status)
        })
        try {
            val api = SettingsWritesV2Api(client,tokens)
            assertFalse(api.delete("ui.theme",SettingScopeIdentity.profile(),null,original) is ApiResult.Success)
            status = HttpStatusCode.NoContent
            assertIs<ApiResult.Success<Unit>>(api.delete("ui.theme",SettingScopeIdentity.profile(),null,original))
            change = true
            assertIs<ApiResult.Error>(api.delete("ui.theme",SettingScopeIdentity.profile(),null,original))
            assertIs<ApiResult.Error>(api.delete("ui.theme",SettingScopeIdentity.profile(),null,original))
            assertEquals(3,sends)
        } finally { client.close() }
    }

    @Test fun receiptMustMatchDeclaredDeviceAndClientFamily() = runTest {
        var settingScope = "profile_device"
        var receiptIdentity = "\"device_id\":\"device\""
        val client = HttpClient(MockEngine {
            respond("""{"key":"ui.theme","scope":"$settingScope","profile_id":"parent",$receiptIdentity,"value":"dark","revision":1}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(io.ktor.client.plugins.DefaultRequest) {
                headers.append("X-Silo-Device-Id","device")
                headers.append("X-Silo-Client-Family","mobile")
            }
        }
        try {
            val api = SettingsWritesV2Api(client,tokens)
            assertIs<ApiResult.Success<StoredSettingValue>>(api.put("ui.theme",SettingScopeIdentity.profileDevice(),JsonPrimitive("dark"),null,owner))
            receiptIdentity = "\"device_id\":\"foreign\""
            assertIs<ApiResult.Error>(api.put("ui.theme",SettingScopeIdentity.profileDevice(),JsonPrimitive("dark"),null,owner))
            settingScope = "profile_client"
            receiptIdentity = "\"client_family\":\"mobile\""
            assertEquals("mobile", assertIs<ApiResult.Success<StoredSettingValue>>(api.put("ui.theme",SettingScopeIdentity.profileClient(),JsonPrimitive("dark"),null,owner)).data.clientFamily)
            receiptIdentity = "\"client_family\":\"tv\""
            assertIs<ApiResult.Error>(api.put("ui.theme",SettingScopeIdentity.profileClient(),JsonPrimitive("dark"),null,owner))
        } finally { client.close() }
    }

    @Test fun v2CapabilityDoesNotPromiseLegacyMutationReceipts() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/settings/contract/capabilities",it.url.encodedPath)
            respond("""{"api_version":1,"revision":12,"supports_batched_effective":true,"supports_idempotent_writes":true}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val caps = assertIs<SettingsCapabilitiesResult.Available>(SettingsApi(client,writesV2=SettingsWritesV2Api(client,tokens)).getContractCapabilities()).capabilities
            assertFalse(caps.supportsIdempotentWrites); assertTrue(caps.supportsBatchedEffective)
        } finally { client.close() }
    }
}
