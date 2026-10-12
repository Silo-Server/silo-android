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
    /** Logins saved per server, which removal and token rotation act as whatever is active. */
    private val savedLogins = mutableMapOf("server" to "login", "cabin" to "login-cabin", "attic" to "login-attic")
    private val authorities = object : DurableLoginAuthorityProvider {
        override suspend fun snapshotDurableLoginAuthority() = owner.also { afterCapture(); afterCapture = {} }
        override suspend fun savedLoginAuthority(serverId: String, profileId: String, profileToken: String?) =
            savedLogins[serverId]?.let {
                val url = if (serverId == "server") "https://example.invalid" else "https://$serverId.invalid"
                DurableLoginAuthority(it, AuthScopeSnapshot.savedLogin(serverId, url, profileId, profileToken, 5))
            }
    }
    private var removedAs: AuthScopeSnapshot? = null
    /** Runs once, right after the next capture of the active login. */
    private var afterCapture: () -> Unit = {}
    /** The profile each server holds this device's registration for: it refuses a removal from any other. */
    private val serverProfiles = mutableMapOf<String, String?>()
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
    private var unreachableUrl: String?=null
    private var available=true
    private var onAvailable: suspend (AuthScopeSnapshot) -> Unit = {}
    private val api=object : PushRegistrationApi {
        override suspend fun available(owner: AuthScopeSnapshot): ApiResult<Boolean> { onAvailable(owner); return ApiResult.Success(available) }
        override suspend fun register(request: PushDeviceRegisterRequest,key:String,generation:Long,owner:AuthScopeSnapshot):ApiResult<PushDeviceRegisterResponse> {
            val record=store.entries.single { it.serverUrl==owner.serverUrl }
            assertEquals(generation,record.generation) // durable before dispatch
            assertEquals(request,record.intent.registration)
            generations+=generation; keys+=key; send()
            failCode?.let { return ApiResult.Error(it,"refused","") }
            if(owner.serverUrl!=unreachableUrl) serverProfiles[owner.serverUrl]=owner.profileId
            if(uncertain || owner.serverUrl==unreachableUrl) return ApiResult.NetworkError(IllegalStateException("lost reply"))
            return ApiResult.Success(PushDeviceRegisterResponse("r$generation",request.pushMode,generation.toString(),"d$generation"))
        }
        override suspend fun delete(deviceId:String,key:String,generation:Long,owner:AuthScopeSnapshot):ApiResult<Unit> {
            generations+=generation; keys+=key; removedAs=owner; send()
            if(serverProfiles[owner.serverUrl].let { it!=null && it!=owner.profileId }) return ApiResult.Error(409,"conflict","")
            return if(uncertain) ApiResult.NetworkError(IllegalStateException("lost reply")) else ApiResult.Success(Unit)
        }
    }
    private fun registrar(provider: AndroidPushTokenProvider=object:AndroidPushTokenProvider { override suspend fun token()=token }) =
        AndroidPushRegistrar(provider,PushRegistrationRepository(api),{"device"},authorities,store,{proof})
    @Test fun unchangedIntentDoesNotAdvanceAndChangedTokenRemovalAndLoginKeepProof() = runTest {
        val r=registrar()
        assertIs<ApiResult.Success<*>>(r.registerToken(token)); r.registerToken(token)
        assertEquals(listOf(1L),generations)
        r.registerToken("n".repeat(80)); r.unregisterDevice("server")
        owner=owner.copy(loginId="next",scope=owner.scope.copy(identityGeneration=2))
        r.registerToken(token)
        assertEquals(listOf(1L,2L,3L,4L),generations); assertEquals(setOf(proof),keys.toSet())
    }
    @Test fun removalActsAsTheRegisteredProfileNotWhoeverIsActive()=runTest {
        val r=registrar(); r.registerToken(token)
        // The server refuses a removal from any other profile, and a refusal is kept for good.
        owner=owner.copy(scope=owner.scope.copy(profileId="other",profileToken="other-pin",identityGeneration=2))
        assertIs<ApiResult.Success<Unit>>(r.unregisterDevice("server"))
        assertEquals("profile",removedAs?.profileId); assertEquals("pin",removedAs?.profileToken)
        val record=store.entries.single()
        assertNull(record.intent.registration); assertTrue(record.acknowledged); assertEquals(listOf(1L,2L),generations)
        assertIs<ApiResult.Error>(r.unregisterDevice("elsewhere")); assertEquals(listOf(1L,2L),generations)
    }
    @Test fun uncertaintyAndRestartReplayExactIntentOnlyForOriginalDurableOwner()=runTest {
        uncertain=true; registrar().registerToken(token)
        val pending=store.entries.single()
        owner=owner.copy(scope=owner.scope.copy(identityGeneration=10,credentialEpoch=7)) // same persisted login on restart
        uncertain=false; registrar().registerToken(token)
        assertEquals(listOf(1L,1L),generations); assertEquals(pending.intent,store.entries.single().intent)
        assertTrue(store.entries.single().acknowledged)
    }
    @Test fun changedIntentOrReplacementLoginCannotRebaseAnUncertainRegistration()=runTest {
        uncertain=true; val r=registrar(); r.registerToken(token)
        assertFalse(r.registerToken("n".repeat(80)) is ApiResult.Success)
        owner=owner.copy(loginId="replacement",scope=owner.scope.copy(identityGeneration=2))
        assertIs<ApiResult.Error>(registrar().registerToken(token)); assertEquals(listOf(1L,1L),generations)
        assertEquals("login",store.entries.single().intent.loginId)
    }
    @Test fun theNextLoginSupersedesARemovalThatSignOutLeftUncertain()=runTest {
        val r=registrar(); r.registerToken(token)
        uncertain=true; assertFalse(r.unregisterDevice("server") is ApiResult.Success)
        uncertain=false
        owner=owner.copy(loginId="next",scope=owner.scope.copy(profileId="kids",profileToken="kids-pin",identityGeneration=2))
        assertIs<ApiResult.Success<*>>(r.registerToken(token))
        assertEquals(listOf(1L,2L,3L),generations); assertEquals(setOf(proof),keys.toSet())
        val record=store.entries.single()
        assertEquals("next",record.intent.loginId); assertTrue(record.acknowledged)
    }
    @Test fun aRotatedTokenReachesEachOtherSavedServerAsItsOwnLoginWhenOneFails()=runTest {
        val r=registrar(); r.registerToken(token)
        val home=owner
        owner=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","adult","https://cabin.invalid","adult-pin",identityGeneration=2))
        r.registerToken(token)
        owner=home.copy(scope=home.scope.copy(identityGeneration=3))
        unreachableUrl=home.scope.serverUrl
        r.registerRotatedToken("n".repeat(80))
        val cabin=store.entries.single { it.intent.serverId=="cabin" }
        assertEquals("n".repeat(80),cabin.intent.registration!!.token); assertTrue(cabin.acknowledged)
        assertEquals("login-cabin" to "adult",cabin.intent.loginId to cabin.intent.profileId)
        assertFalse(store.entries.single { it.intent.serverId=="server" }.acknowledged)
    }
    @Test fun anotherServersRegistrationDoesNotSupersedeARemoval()=runTest {
        val r=registrar(); r.registerToken(token)
        val home=owner
        owner=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","adult","https://cabin.invalid","adult-pin",identityGeneration=2))
        r.registerToken(token)
        owner=home.copy(scope=home.scope.copy(identityGeneration=3))
        val removing=CompletableDeferred<Unit>(); val proceed=CompletableDeferred<Unit>()
        onAvailable={ if(it.serverUrl=="https://cabin.invalid") { removing.complete(Unit); proceed.await() } }
        val removal=async { r.unregisterDevice("cabin") }
        removing.await()
        val registration=async { r.registerToken("n".repeat(80)) }
        proceed.complete(Unit)
        assertIs<ApiResult.Success<Unit>>(removal.await()); assertIs<ApiResult.Success<*>>(registration.await())
        assertNull(store.entries.single { it.intent.serverId=="cabin" }.intent.registration)
    }
    @Test fun unreadableRecordsFailARemovalInsteadOfThrowingIntoSignOut()=runTest {
        store.broken=true
        assertEquals("push_persistence_unavailable",assertIs<ApiResult.Error>(registrar().unregisterDevice("server")).error)
    }
    @Test fun theNextLoginSupersedesARegistrationThatSignOutCouldNotRemove()=runTest {
        uncertain=true; val r=registrar(); r.registerToken(token)
        uncertain=false; available=false
        assertFalse(r.unregisterDevice("server") is ApiResult.Success)
        available=true
        owner=owner.copy(loginId="next",scope=owner.scope.copy(identityGeneration=2))
        assertIs<ApiResult.Success<*>>(r.registerToken(token))
        assertEquals(listOf(1L,2L),generations); assertEquals("next",store.entries.single().intent.loginId)
    }
    @Test fun theNextForegroundGivesOtherServersARotatedTokenTheyMissed()=runTest {
        val r=registrar(); r.registerToken(token)
        val home=owner
        owner=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","adult","https://cabin.invalid","adult-pin",identityGeneration=2))
        r.registerToken(token)
        owner=home.copy(scope=home.scope.copy(identityGeneration=3))
        val rotated="n".repeat(80)
        registrar(object:AndroidPushTokenProvider { override suspend fun token()=rotated }).registerOtherServersIfBehind()
        val cabin=store.entries.single { it.intent.serverId=="cabin" }
        assertEquals(rotated,cabin.intent.registration!!.token); assertTrue(cabin.acknowledged)
        assertEquals("login-cabin" to "adult",cabin.intent.loginId to cabin.intent.profileId)
        assertEquals(token,store.entries.single { it.intent.serverId=="server" }.intent.registration!!.token)
    }
    @Test fun catchingUpATokenNeitherCancelsNorUndoesAServersRemoval()=runTest {
        var current=token
        val r=registrar(object:AndroidPushTokenProvider { override suspend fun token()=current }); r.registerToken(token)
        val home=owner
        for ((server,profile) in listOf("cabin" to "adult","attic" to "guest")) {
            owner=DurableLoginAuthority("login-$server",AuthScopeSnapshot(server,profile,"https://$server.invalid","$profile-pin",identityGeneration=2))
            r.registerToken(token)
        }
        owner=home.copy(scope=home.scope.copy(identityGeneration=3))
        current="n".repeat(80)
        // The attic is removed while the catch-up is busy with the cabin and has yet to reach it.
        var removal: Deferred<ApiResult<Unit>>?=null
        onAvailable={ if(it.serverUrl=="https://cabin.invalid" && removal==null) { removal=async { r.unregisterDevice("attic") }; yield() } }
        r.registerOtherServersIfBehind()
        assertIs<ApiResult.Success<Unit>>(removal!!.await())
        assertNull(store.entries.single { it.intent.serverId=="attic" }.intent.registration)
        assertEquals(current,store.entries.single { it.intent.serverId=="cabin" }.intent.registration!!.token)
    }
    @Test fun aRemovalActsAsTheProfileARegistrationFinishingFirstLeft()=runTest {
        val r=registrar(); r.registerToken(token)
        // A profile switch's registration is being written when sign-out starts removing.
        owner=owner.copy(scope=owner.scope.copy(profileId="kids",profileToken="kids-pin",identityGeneration=2))
        var removal: Deferred<ApiResult<Unit>>?=null
        store.onWrite={ if(removal==null) { removal=async { r.unregisterDevice("server") }; yield() } }
        assertIs<ApiResult.Success<*>>(r.registerToken(token))
        assertIs<ApiResult.Success<Unit>>(removal!!.await())
        assertEquals("kids",removedAs?.profileId)
        val record=store.entries.single(); assertNull(record.refusal); assertNull(record.intent.registration)
    }
    @Test fun aRemovalMarksTheRecordWithoutWaitingOnARequestInFlight()=runTest {
        uncertain=true
        val entered=CompletableDeferred<Unit>(); val hold=CompletableDeferred<Unit>()
        send={ entered.complete(Unit); hold.await() }
        val r=registrar(); val registration=async { r.registerToken(token) }
        entered.await()
        // Sign-out removes while that registration's request is still out, and its budget runs out waiting.
        assertEquals("push_unregister_timed_out",assertIs<ApiResult.Error>(r.unregisterDevice("server")).error)
        hold.complete(Unit); registration.await()
        send={}; uncertain=false
        owner=owner.copy(loginId="next",scope=owner.scope.copy(identityGeneration=2))
        assertIs<ApiResult.Success<*>>(r.registerToken(token))
        assertEquals("next",store.entries.single().intent.loginId)
    }
    @Test fun aRemovalLeftPendingIsSentAgainByTheNextOne()=runTest {
        val r=registrar(); r.registerToken(token)
        uncertain=true; assertFalse(r.unregisterDevice("server") is ApiResult.Success)
        uncertain=false
        assertIs<ApiResult.Success<Unit>>(r.unregisterDevice("server"))
        assertEquals(listOf(1L,2L,2L),generations)
        val record=store.entries.single(); assertNull(record.intent.registration); assertTrue(record.acknowledged)
    }
    @Test fun aServerWhoseLoginHoldsItAgainIsCaughtUpAgain()=runTest {
        var current=token
        val r=registrar(object:AndroidPushTokenProvider { override suspend fun token()=current }); r.registerToken(token)
        val home=owner.copy(scope=owner.scope.copy(identityGeneration=3))
        val cabin=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","adult","https://cabin.invalid","adult-pin",identityGeneration=2))
        fun cabinToken()=store.entries.single { it.intent.serverId=="cabin" }.intent.registration!!.token
        // A cabin sign-out that could not reach the server marks its record, then stops: the login stays.
        suspend fun signOutOfCabinThatStops() { available=false; assertFalse(r.unregisterDevice("cabin") is ApiResult.Success); available=true }

        owner=cabin; uncertain=true; r.registerToken(token); uncertain=false
        signOutOfCabinThatStops()
        assertIs<ApiResult.Success<*>>(r.registerToken(token)) // re-sent and acknowledged
        owner=home; current="n".repeat(80); r.registerOtherServersIfBehind()
        assertEquals(current,cabinToken())

        owner=cabin; signOutOfCabinThatStops()
        assertIs<ApiResult.Success<*>>(r.registerToken(current)) // already held: nothing to send
        owner=home; current="m".repeat(80); r.registerOtherServersIfBehind()
        assertEquals(current,cabinToken())
    }
    @Test fun aCatchUpRenewsOnlyTheRegistrationItFoundBehind()=runTest {
        var current=token
        val r=registrar(object:AndroidPushTokenProvider { override suspend fun token()=current }); r.registerToken(token)
        val home=owner.copy(scope=owner.scope.copy(identityGeneration=9))
        for ((server,profile) in listOf("attic" to "guest","cabin" to "adult")) {
            owner=DurableLoginAuthority("login-$server",AuthScopeSnapshot(server,profile,"https://$server.invalid","$profile-pin",identityGeneration=2))
            r.registerToken(token)
        }
        owner=home; current="n".repeat(80)
        // While the catch-up is busy with the attic, the cabin's login switches profile and registers it.
        var switched=false
        onAvailable={ if(it.serverUrl=="https://attic.invalid" && !switched) {
            switched=true
            owner=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","kids","https://cabin.invalid","kids-pin",identityGeneration=4))
            assertIs<ApiResult.Success<*>>(r.registerToken(token)); owner=home
        } }
        r.registerOtherServersIfBehind()
        assertEquals("kids",store.entries.single { it.intent.serverId=="cabin" }.intent.profileId)
    }
    @Test fun aRotationGivesTheServerItBeganOnTheNewTokenEvenIfAnotherBecomesActive()=runTest {
        val r=registrar(); r.registerToken(token)
        val home=owner
        val cabin=DurableLoginAuthority("login-cabin",AuthScopeSnapshot("cabin","adult","https://cabin.invalid","adult-pin",identityGeneration=2))
        owner=cabin; r.registerToken(token)
        owner=home.copy(scope=home.scope.copy(identityGeneration=3))
        // The user switches to the cabin just after the rotation sees home as active.
        afterCapture={ owner=cabin.copy(scope=cabin.scope.copy(identityGeneration=4)) }
        r.registerRotatedToken("n".repeat(80))
        for (record in store.entries) assertEquals("n".repeat(80),record.intent.registration!!.token,record.intent.serverId)
    }
    @Test fun aRestorePutsBackARemovedRegistrationOnlyWhileItsLoginRemains()=runTest {
        val r=registrar(); r.registerToken(token)
        assertIs<ApiResult.Success<Unit>>(r.unregisterDevice("server"))
        assertIs<ApiResult.Success<*>>(r.restoreRegistration("server"))
        val restored=store.entries.single()
        assertEquals(token,restored.intent.registration?.token); assertEquals("login",restored.intent.loginId)
        assertTrue(restored.acknowledged); assertFalse(restored.ownerLeaving)

        assertIs<ApiResult.Success<Unit>>(r.unregisterDevice("server"))
        savedLogins.remove("server") // the sign-out went through after all
        assertIs<ApiResult.Error>(r.restoreRegistration("server"))
        assertNull(store.entries.single().intent.registration)
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
