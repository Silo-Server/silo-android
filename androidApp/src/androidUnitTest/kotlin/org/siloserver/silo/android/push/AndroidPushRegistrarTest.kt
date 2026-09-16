package org.siloserver.silo.android.push

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.PushRegistrationApi
import org.siloserver.silo.repository.PushRegistrationRepository
import kotlin.test.*

class AndroidPushRegistrarTest {
    private var owner = DurableLoginAuthority("login", AuthScopeSnapshot("server","profile","https://example.invalid","pin",identityGeneration=1))
    private val token = "t".repeat(80)
    private val proof = "k".repeat(43)
    private val authorities = object : DurableLoginAuthorityProvider { override suspend fun snapshotDurableLoginAuthority() = owner }
    private class Store : PushInstallationStore {
        var entries = emptyList<PushInstallation>()
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        var broken=false
        override suspend fun read(): List<PushInstallation> { onRead(); check(!broken); return entries }
        override suspend fun write(records: List<PushInstallation>) { onWrite(); check(!broken); entries=records }
    }
    private val store=Store()
    private val generations=mutableListOf<Long>()
    private val keys=mutableListOf<String>()
    private var send: suspend () -> Unit = {}
    private var failCode: Int?=null
    private var uncertain=false
    private var available=true
    private val api=object : PushRegistrationApi {
        override suspend fun available(owner: AuthScopeSnapshot): ApiResult<Boolean> = ApiResult.Success(available)
        override suspend fun register(request: PushDeviceRegisterRequest,key:String,generation:Long,owner:AuthScopeSnapshot):ApiResult<PushDeviceRegisterResponse> {
            assertEquals(generation,store.entries.single().generation) // durable before dispatch
            assertEquals(request,store.entries.single().intent.registration)
            generations+=generation; keys+=key; send()
            failCode?.let { return ApiResult.Error(it,"refused","") }
            if(uncertain) return ApiResult.NetworkError(IllegalStateException("lost reply"))
            return ApiResult.Success(PushDeviceRegisterResponse("r$generation",request.pushMode,generation.toString(),"d$generation"))
        }
        override suspend fun delete(deviceId:String,key:String,generation:Long,owner:AuthScopeSnapshot):ApiResult<Unit> {
            generations+=generation; keys+=key; send(); return ApiResult.Success(Unit)
        }
    }
    private fun registrar(provider: AndroidPushTokenProvider=object:AndroidPushTokenProvider { override suspend fun token()=token }) =
        AndroidPushRegistrar(provider,PushRegistrationRepository(api),{"device"},authorities,store,{proof})
    @Test fun unchangedIntentDoesNotAdvanceAndChangedTokenRemovalAndLoginKeepProof() = runTest {
        val r=registrar()
        assertIs<ApiResult.Success<*>>(r.registerToken(token)); r.registerToken(token)
        assertEquals(listOf(1L),generations)
        r.registerToken("n".repeat(80)); r.unregisterDevice()
        owner=owner.copy(loginId="next",scope=owner.scope.copy(identityGeneration=2))
        r.registerToken(token)
        assertEquals(listOf(1L,2L,3L,4L),generations); assertEquals(setOf(proof),keys.toSet())
    }
    @Test fun uncertaintyAndRestartReplayExactIntentOnlyForOriginalDurableOwner()=runTest {
        uncertain=true; registrar().registerToken(token)
        val pending=store.entries.single()
        owner=owner.copy(scope=owner.scope.copy(identityGeneration=10,credentialEpoch=7)) // same persisted login on restart
        uncertain=false; registrar().registerToken(token)
        assertEquals(listOf(1L,1L),generations); assertEquals(pending.intent,store.entries.single().intent)
        assertTrue(store.entries.single().acknowledged)
    }
    @Test fun changedIntentOrReplacementLoginCannotRebaseUncertainty()=runTest {
        uncertain=true; val r=registrar(); r.registerToken(token)
        assertFalse(r.registerToken("n".repeat(80)) is ApiResult.Success)
        owner=owner.copy(loginId="replacement",scope=owner.scope.copy(identityGeneration=2))
        assertIs<ApiResult.Error>(registrar().registerToken(token)); assertEquals(listOf(1L,1L),generations)
        assertEquals("login",store.entries.single().intent.loginId)
    }
    @Test fun conflictStaysDurableAndNeverRotatesProofOrGeneration()=runTest {
        failCode=409; registrar().registerToken(token)
        failCode=null
        assertIs<ApiResult.Error>(registrar().registerToken(token))
        assertIs<ApiResult.Error>(registrar().registerToken("n".repeat(80)))
        assertEquals(listOf(1L),generations); assertEquals(1,store.entries.single().generation)
    }
    @Test fun replacementDuringTokenAwaitDoesNotAllocateOrSend()=runTest {
        val r=registrar(object:AndroidPushTokenProvider { override suspend fun token():String {
            owner=owner.copy(loginId="replacement",scope=owner.scope.copy(identityGeneration=2)); return token
        } })
        assertIs<ApiResult.Error>(r.registerIfAvailable()); assertTrue(store.entries.isEmpty()); assertTrue(generations.isEmpty())
    }
    @Test fun replacementDuringStoreReadOrWriteCannotSend()=runTest {
        store.onRead={owner=owner.copy(scope=owner.scope.copy(identityGeneration=2))}
        assertIs<ApiResult.Error>(registrar().registerToken(token)); assertTrue(generations.isEmpty())
        store.onRead={}; store.onWrite={owner=owner.copy(scope=owner.scope.copy(identityGeneration=3))}
        assertIs<ApiResult.Error>(registrar().registerToken(token)); assertTrue(generations.isEmpty())
        assertFalse(store.entries.single().acknowledged)
    }
    @Test fun lateReplyCannotAcknowledgeReplacementOwner()=runTest {
        send={owner=owner.copy(loginId="replacement",scope=owner.scope.copy(identityGeneration=2))}
        assertIs<ApiResult.Error>(registrar().registerToken(token)); assertFalse(store.entries.single().acknowledged)
    }
    @Test fun cancellationLeavesExactPersistedIntentAndConcurrentDuplicatesSerialize()=runTest {
        val entered=CompletableDeferred<Unit>(); val hold=CompletableDeferred<Unit>()
        send={entered.complete(Unit);hold.await()}
        val r=registrar(); val first=launch{r.registerToken(token)}
        entered.await(); first.cancelAndJoin()
        assertFalse(store.entries.single().acknowledged)
        send={}; coroutineScope { awaitAll(async{r.registerToken(token)},async{r.registerToken(token)}) }
        assertEquals(listOf(1L,1L),generations)
    }
    @Test fun unavailableOrBrokenPersistenceNeverSendsAndOverflowNeverResets()=runTest {
        available=false; assertIs<ApiResult.Error>(registrar().registerToken(token)); assertTrue(store.entries.isEmpty())
        available=true; store.broken=true; assertIs<ApiResult.Error>(registrar().registerToken(token)); assertTrue(generations.isEmpty())
        store.broken=false; registrar().registerToken(token)
        store.entries=listOf(store.entries.single().copy(generation=Long.MAX_VALUE,receipt=store.entries.single().receipt!!.copy(generation=Long.MAX_VALUE.toString())))
        assertIs<ApiResult.Error>(registrar().registerToken("n".repeat(80)))
        assertEquals(listOf(1L),generations); assertEquals(Long.MAX_VALUE,store.entries.single().generation)
    }
    @Test fun delayedTokenLookupCannotOverwriteNewerCallback()=runTest {
        val entered=CompletableDeferred<Unit>(); val oldToken=CompletableDeferred<String>()
        val r=registrar(object:AndroidPushTokenProvider { override suspend fun token():String {
            entered.complete(Unit); return oldToken.await()
        } })
        val delayed=async { r.registerIfAvailable() }
        entered.await()
        assertIs<ApiResult.Success<*>>(r.registerToken("n".repeat(80)))
        oldToken.complete(token)
        assertIs<ApiResult.Error>(delayed.await())
        assertEquals(listOf(1L),generations)
        assertEquals("n".repeat(80),store.entries.single().intent.registration!!.token)
    }

    @Test fun tokenRotationReconcilesExactUncertainIntentBeforeAllocatingNext()=runTest {
        uncertain=true; val r=registrar(); r.registerToken(token)
        uncertain=false
        assertIs<ApiResult.Success<*>>(r.registerToken("n".repeat(80)))
        assertEquals(listOf(1L,1L,2L),generations)
        assertEquals("n".repeat(80),store.entries.single().intent.registration!!.token)
        assertTrue(store.entries.single().acknowledged)
    }

}
