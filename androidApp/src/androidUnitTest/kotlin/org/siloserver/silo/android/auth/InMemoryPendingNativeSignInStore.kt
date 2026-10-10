package org.siloserver.silo.android.auth

/** A [PendingNativeSignInStore] for tests; the app keeps the flow in encrypted preferences. */
class InMemoryPendingNativeSignInStore : PendingNativeSignInStore {
    private var pending: PendingNativeSignIn? = null
    override fun load(): PendingNativeSignIn? = pending
    override fun save(pending: PendingNativeSignIn) { this.pending = pending }
    override fun clear() { pending = null }
}

/** An [AccountChoiceStore] for tests; the app keeps the requests in encrypted preferences. */
class InMemoryAccountChoiceStore : AccountChoiceStore {
    private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun request(serverEntryId: String) { requested += serverEntryId }
    override fun isRequested(serverEntryId: String): Boolean = serverEntryId in requested
    override fun clear(serverEntryId: String) { requested -= serverEntryId }
}
