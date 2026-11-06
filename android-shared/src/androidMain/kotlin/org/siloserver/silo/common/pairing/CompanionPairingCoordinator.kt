package org.siloserver.silo.common.pairing

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.common.R
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthUnavailableException
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.currentAccountOn
import org.siloserver.silo.pairing.PairingEndpoint
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingProtocol
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.ServerIdentityRepository

data class CompanionPairingTarget(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val state: String? = null,
    val version: Int = PairingProtocol.VERSION,
    /** Fresh TXT-record nonce for one TV advertising session. */
    val sessionId: String? = null,
    /** DNS-SD instance name used by onServiceLost; it can differ from the TXT display name. */
    val serviceName: String = name,
    /** TXT `srv`: the deployment identity a signed-out (`st=login`) TV wants. */
    val serverIdentity: String? = null,
    /** Found as `_silopair-roku._tcp`: dialed over plain TCP, never TLS-PSK, and signed in to one server. */
    val isRoku: Boolean = false,
    /**
     * TXT `acct=1`: the TV takes any number of pushes in one session, the
     * same server again included, and keeps each sign-in as its own account.
     */
    val acceptsAccounts: Boolean = false,
    /** TXT `lim=<n>`: the most sign-ins the TV can keep from one session; null when it states none. */
    val signInLimit: Int? = null,
) {
    /**
     * A Roku without `acct=1` keeps one server and refuses a second push
     * (silo-roku `docs/companion-pairing.md` section 4).
     */
    val keepsOneServer: Boolean get() = isRoku && !acceptsAccounts

    /** How the chooser lets the person pick accounts for this TV (contract §C.1). */
    val selectionMode: CompanionSelectionMode
        get() = when {
            acceptsAccounts -> CompanionSelectionMode.Several
            keepsOneServer -> CompanionSelectionMode.One
            else -> CompanionSelectionMode.OnePerServer
        }

    /** What the phone calls this device in its copy. */
    val deviceNoun: String get() = if (isRoku) "Roku" else "TV"

    /**
     * A TV on its sign-in screen for one server. Older TVs advertise no `st`
     * (or `setup`); anything that isn't `login` is treated as a setup TV.
     */
    val isSignedOutTv: Boolean get() = state == PairingReceiverState.Login.wire

    /** Only `setup` and `login` TVs are offered; an unknown state is a newer TV this build can't serve. */
    val isOfferable: Boolean
        get() = state == null || state == PairingReceiverState.Setup.wire ||
            (isSignedOutTv && !serverIdentity.isNullOrBlank())
}

/**
 * One saved account on a [CompanionPairingServer]. [id] is its local account
 * id ([org.siloserver.silo.model.server.AccountEntry.localAccountId]), or
 * null for "the server's current account" when the phone lists none.
 */
data class CompanionPairingAccount(
    val id: String?,
    val username: String?,
    /** The account this phone is using on its server. */
    val isCurrent: Boolean = false,
    /** Its session expired here: shown, but it can't approve until it signs in again. */
    val needsSignIn: Boolean = false,
) {
    val canApprove: Boolean get() = !needsSignIn
}

data class CompanionPairingServer(
    val id: String,
    val url: String,
    val displayName: String,
    /** The phone's active server, preselected in the chooser. */
    val isActive: Boolean = false,
    /** The server's saved accounts, the phone's current one first. */
    val accounts: List<CompanionPairingAccount> = emptyList(),
) {
    /** The accounts to offer: the saved ones, or the server's current account when none are listed. */
    val offeredAccounts: List<CompanionPairingAccount>
        get() = accounts.ifEmpty { listOf(CompanionPairingAccount(id = null, username = null, isCurrent = true)) }

    val approvableAccounts: List<CompanionPairingAccount> get() = offeredAccounts.filter { it.canApprove }
}

/** One thing the phone sends: [account] on [server], approved with that account's credentials. */
data class CompanionPairingDestination(
    val server: CompanionPairingServer,
    val account: CompanionPairingAccount,
) {
    /** Stable chooser key. */
    val key: String get() = destinationKey(server.id, account.id)

    companion object {
        fun destinationKey(serverId: String, accountId: String?): String = "$serverId/${accountId.orEmpty()}"
    }
}

/**
 * What the phone knows about a saved server beyond its URL: the deployment
 * identity verified at that URL and the other addresses it offers
 * (`GET /api/v2/system/connections`). Sent in `pushServer` so the TV can
 * verify the server and reach it at another address (silo-apple parity).
 */
interface CompanionServerIdentitySource {
    /** Verified identity of [server], or null when it can't be verified. */
    suspend fun identity(server: CompanionPairingServer): String?

    /** Addresses [server] offers other devices, or null when unavailable. */
    suspend fun endpoints(server: CompanionPairingServer): List<PairingEndpoint>?

    /** The servers among [servers] whose verified identity is [serverId], in the order given. */
    suspend fun serversWithIdentity(
        servers: List<CompanionPairingServer>,
        serverId: String,
    ): List<CompanionPairingServer> = servers.filter { identity(it) == serverId }
}

/** Production identity source over the saved-server registry. */
class RegistryCompanionServerIdentitySource(
    private val registry: ServerRegistry,
    private val identities: ServerIdentityRepository,
    private val identityApi: ServerIdentityApi,
    private val identityTransitions: IdentityTransitionBarrier,
) : CompanionServerIdentitySource {
    override suspend fun identity(server: CompanionPairingServer): String? {
        val entry = registry.entries.value.firstOrNull { it.id == server.id } ?: return null
        return identities.identityOf(entry)
    }

    /** Recorded identities match without the network; the rest are probed in parallel. */
    override suspend fun serversWithIdentity(
        servers: List<CompanionPairingServer>,
        serverId: String,
    ): List<CompanionPairingServer> {
        val candidates = registry.entries.value.filter { entry -> servers.any { it.id == entry.id } }
        val matched = identities.entriesFor(serverId, candidates).map { it.id }.toSet()
        return servers.filter { it.id in matched }
    }

    override suspend fun endpoints(server: CompanionPairingServer): List<PairingEndpoint>? =
        identityApi.connections(
            AuthScopeSnapshot.profileless(server.id, server.url, identityTransitions.generation.value),
        )?.endpoints?.takeIf { it.isNotEmpty() }
}

data class CompanionPairingServerSnapshot(
    val activeServerId: String?,
    val servers: List<CompanionPairingServer>,
)

/**
 * A code comparison. People compare [userCode], the code the TV shows on
 * screen (from the server's lookup, not the TV's frame). The match words are
 * checked programmatically; [serverMatchCode] is shown only on a secondary
 * line for TV apps released before user codes, which show the words alone.
 * Drop that line on the same schedule as the web `/activate` card's "Older
 * TV apps show ..." line.
 *
 * The first target is always compared. [isFollowUp] marks a later target
 * that needs its own comparison (its server has no session binding, or the
 * phone holds no trusted binding yet): the card names the account and
 * server it is for.
 */
data class CompanionPairingApproval(
    val targetName: String,
    val serverName: String,
    val userCode: String,
    val serverMatchCode: String,
    /** The server's host, shown next to its name ("Home (silo.example.com)"). */
    val serverHost: String = "",
    /** The account approving signs the TV in as; null when it couldn't be read (the card says "with your account"). */
    val accountName: String? = null,
    val isFollowUp: Boolean = false,
    /** This target's place in the session, 1-based, and how many are being sent. */
    val position: Int = 1,
    val total: Int = 1,
)

/**
 * What the phone's card says about a session, kept as catalog messages and
 * read in the interface language where it is shown ([text]).
 */
sealed interface CompanionPairingText {
    /** [id] filled with [names] (TV, server and account names) in its placeholders' order. */
    data class Message(@StringRes val id: Int, val names: List<String> = emptyList()) : CompanionPairingText

    /** Text that arrives already written: a server name, or a notice from elsewhere in the app. */
    data class Verbatim(val text: String) : CompanionPairingText

    /** One target's failure, naming the account it was for. */
    data class FailedFor(val account: String, val server: String, val reason: CompanionPairingText) : CompanionPairingText

    /** One line each. */
    data class Lines(val lines: List<CompanionPairingText>) : CompanionPairingText
}

/** [CompanionPairingText] in the interface language; names are isolated in a right-to-left interface. */
@Composable
fun CompanionPairingText.text(): String {
    LocalConfiguration.current // read again when the language changes
    return resolve(LocalContext.current.resources, LocalLayoutDirection.current == LayoutDirection.Rtl)
}

fun CompanionPairingText.resolve(resources: Resources, rtl: Boolean): String {
    fun name(value: String) = if (rtl) "\u2068$value\u2069" else value
    return when (this) {
        is CompanionPairingText.Message -> resources.getString(id, *names.map(::name).toTypedArray())
        is CompanionPairingText.Verbatim -> text
        is CompanionPairingText.FailedFor ->
            resources.getString(R.string.pairing_summary_failedFor, name(account), name(server), reason.resolve(resources, rtl))
        is CompanionPairingText.Lines -> lines.joinToString("\n") { it.resolve(resources, rtl) }
    }
}

/** How one target ended. [failure] is the phone's text for it; null when it signed in. */
data class CompanionTargetOutcome(
    val serverName: String,
    val accountName: String?,
    val failure: CompanionPairingText? = null,
) {
    val signedIn: Boolean get() = failure == null
}

/**
 * The per-target summary (contract §C.6): one line per server that signed
 * in, naming its accounts ("Home — alex, sam"), then one line per failure
 * with its text, naming the account when known.
 */
fun companionSummary(outcomes: List<CompanionTargetOutcome>): List<CompanionPairingText> {
    val signedIn = outcomes.filter { it.signedIn }
        .groupBy { it.serverName }
        .map { (server, targets) ->
            val names = targets.mapNotNull { it.accountName }.distinct()
            if (names.isEmpty()) {
                CompanionPairingText.Verbatim(server)
            } else {
                CompanionPairingText.Message(R.string.pairing_summary_signedIn, listOf(server, names.joinToString(", ")))
            }
        }
    val failed = outcomes.filterNot { it.signedIn }.map { outcome ->
        val reason = outcome.failure ?: CompanionPairingText.Verbatim("")
        outcome.accountName?.let { CompanionPairingText.FailedFor(it, outcome.serverName, reason) } ?: reason
    }
    return signedIn + failed
}

/** [companionSummary] as the card shows it, one string per line. */
@Composable
fun companionSummaryLines(outcomes: List<CompanionTargetOutcome>): List<String> = companionSummary(outcomes).map { it.text() }

sealed class CompanionPairingStatus {
    data object Idle : CompanionPairingStatus()
    data class Connecting(val targetName: String) : CompanionPairingStatus()
    data class PickServers(val targetName: String) : CompanionPairingStatus()
    data class PushingServer(
        val targetName: String,
        val serverName: String,
        val accountName: String? = null,
        val position: Int = 1,
        val total: Int = 1,
    ) : CompanionPairingStatus()
    data class AwaitingMatchConfirmation(val approval: CompanionPairingApproval) : CompanionPairingStatus()
    data class Approving(
        val targetName: String,
        val serverName: String,
        val accountName: String? = null,
        val position: Int = 1,
        val total: Int = 1,
    ) : CompanionPairingStatus()
    data class SignedIn(
        val targetName: String,
        val serverName: String,
        val serverCount: Int,
        val accountName: String? = null,
    ) : CompanionPairingStatus()

    /** At least one target signed in. [outcomes] lists every target sent, in order. */
    data class Completed(val targetName: String, val outcomes: List<CompanionTargetOutcome>) : CompanionPairingStatus() {
        val serverNames: List<String> get() = outcomes.filter { it.signedIn }.map { it.serverName }.distinct()
        val failures: List<CompanionTargetOutcome> get() = outcomes.filterNot { it.signedIn }
    }

    data class Failed(val targetName: String, val text: CompanionPairingText) : CompanionPairingStatus() {
        val message: String @Composable get() = text.text()
    }
}

sealed class CompanionPairingResult {
    /** [serverCount] targets signed in; [failures] are the ones that didn't. */
    data class Completed(
        val serverCount: Int,
        val failures: List<CompanionTargetOutcome> = emptyList(),
    ) : CompanionPairingResult()
    data class Failed(val text: CompanionPairingText) : CompanionPairingResult()
}

interface CompanionPairingServerStore {
    suspend fun snapshot(): CompanionPairingServerSnapshot
}

/** Device-login decisions taken with one saved account's credentials ([CompanionPairingDestination.account]). */
interface CompanionDeviceLoginApprover {
    suspend fun lookup(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginLookupResponse>
    suspend fun approve(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginDecisionResponse>
    suspend fun deny(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginDecisionResponse>

    /**
     * The account approving signs the TV in as, read from the server with
     * that account's credentials (the approval card names it, like the web
     * `/activate` card and the iPhone app). Null when it can't be read; the
     * card then falls back to the saved username, or says "with your
     * account" when that is unknown too. Never a reason to stop.
     */
    suspend fun accountName(destination: CompanionPairingDestination): String? = null
}

fun interface CompanionPairingTransportFactory {
    suspend fun connect(target: CompanionPairingTarget): PairingTransport
}

/**
 * The phone's saved servers, each with its accounts: the active server first,
 * then most recently used; on each, the account in use first, then in the
 * order they were added. An account can approve when it holds a token and
 * hasn't expired; the rest are listed as needing sign-in again.
 */
class RegistryCompanionPairingServerStore(
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier? = null,
) : CompanionPairingServerStore {
    override suspend fun snapshot(): CompanionPairingServerSnapshot {
        val activeId = registry.activeServerId.value
        val accounts = registry.accounts.value
        val generation = identityTransitions?.generation?.value
        val servers = registry.entries.value
            .filter { it.url.isNotBlank() }
            .sortedWith(compareByDescending<ServerEntry> { it.id == activeId }.thenByDescending { it.lastUsedAtEpochMs })
            .mapNotNull { entry ->
                val current = registry.currentAccountOn(entry.id)?.localAccountId
                val saved = accounts.filter { it.serverId == entry.id }
                    .sortedWith(compareByDescending<AccountEntry> { it.localAccountId == current }.thenBy { it.addedAtEpochMs })
                    .map { account ->
                        val hasToken = !account.needsSignIn && (
                            if (generation == null) {
                                account.localAccountId == current && !tokenManager.getAccessTokenForScope(entry.id).isNullOrBlank()
                            } else {
                                !tokenManager.getAccessTokenForScope(
                                    AuthScopeSnapshot.profileless(entry.id, entry.url, generation, account.localAccountId),
                                ).isNullOrBlank()
                            }
                            )
                        CompanionPairingAccount(
                            id = account.localAccountId,
                            username = account.username,
                            isCurrent = account.localAccountId == current,
                            needsSignIn = !hasToken,
                        )
                    }
                // A server whose session predates saved accounts: its one session.
                val listed = saved.ifEmpty {
                    if (tokenManager.getAccessTokenForScope(entry.id).isNullOrBlank()) return@mapNotNull null
                    emptyList()
                }
                CompanionPairingServer(
                    id = entry.id,
                    url = entry.url,
                    displayName = entry.displayName,
                    isActive = entry.id == activeId,
                    accounts = listed,
                )
            }
        return CompanionPairingServerSnapshot(activeServerId = activeId, servers = servers)
    }
}

class RepositoryCompanionDeviceLoginApprover(
    private val repository: DeviceLoginRepository,
    private val identityTransitions: IdentityTransitionBarrier,
    /** The account on a scope's server, read with that account's own credentials. */
    private val accountNameOf: suspend (AuthScopeSnapshot) -> String? = { null },
) : CompanionDeviceLoginApprover {
    override suspend fun accountName(destination: CompanionPairingDestination): String? =
        runCatching { accountNameOf(destination.scope()) }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun lookup(
        destination: CompanionPairingDestination,
        code: String,
    ): ApiResult<DeviceLoginLookupResponse> = repository.lookup(destination.scope(), code)

    override suspend fun approve(
        destination: CompanionPairingDestination,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = repository.approve(destination.scope(), code)

    override suspend fun deny(
        destination: CompanionPairingDestination,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = repository.deny(destination.scope(), code)

    /** The chosen account's profileless scope; a null account id is the server's current account. */
    private fun CompanionPairingDestination.scope() =
        AuthScopeSnapshot.profileless(server.id, server.url, identityTransitions.generation.value, account.id)
}

class CompanionPairingCoordinator(
    private val serverStore: CompanionPairingServerStore,
    private val deviceLoginApprover: CompanionDeviceLoginApprover,
    private val transportFactory: CompanionPairingTransportFactory,
    private val identitySource: CompanionServerIdentitySource? = null,
) {
    private val _status = MutableStateFlow<CompanionPairingStatus>(CompanionPairingStatus.Idle)
    val status: StateFlow<CompanionPairingStatus> = _status.asStateFlow()

    fun reset() {
        _status.value = CompanionPairingStatus.Idle
    }

    /**
     * The saved server a signed-out TV ([CompanionPairingTarget.isSignedOutTv])
     * wants, matched by verified identity against its `srv`, with at least
     * one account that can approve; null when this phone doesn't hold it and
     * must not offer the TV.
     */
    suspend fun serverForSignedOutTv(target: CompanionPairingTarget): CompanionPairingServer? {
        val wanted = target.serverIdentity?.takeIf { target.isSignedOutTv && it.isNotBlank() } ?: return null
        val source = identitySource ?: return null
        val candidates = serverStore.snapshot().orderedServers().filter { it.approvableAccounts.isNotEmpty() }
        return source.serversWithIdentity(candidates, wanted).firstOrNull()
    }

    /**
     * [signInServer]: for a signed-out TV, the server [serverForSignedOutTv]
     * already matched when the offer was made; resolved again only when
     * absent. [signInAccountId]: the account the person chose on its card
     * (null: the one in use on that server). One account per sign-in screen.
     *
     * [chooseAccounts] answers the chooser for a setup TV with the keys
     * ([CompanionPairingDestination.key]) to send. [confirmMatch] asks the
     * person to compare a code: always for the first target, and for a later
     * one only when its binding can't be checked (contract §C.5).
     *
     * One target failing never ends the session: the rest are sent, `done`
     * follows, and the result lists each target. Only the TV hanging up,
     * cancelling or going silent, or the person declining the first code,
     * ends it early.
     */
    suspend fun pair(
        target: CompanionPairingTarget,
        signInServer: CompanionPairingServer? = null,
        signInAccountId: String? = null,
        chooseAccounts: suspend (CompanionAccountChoice) -> Set<String> = { it.initialSelection() },
        confirmMatch: suspend (CompanionPairingApproval) -> Boolean = { true },
    ): CompanionPairingResult {
        val snapshot = serverStore.snapshot()
        val orderedServers = snapshot.orderedServers()
        // A signed-out TV wants exactly one server, the one it advertised:
        // skip the chooser and push only that one, as one account. It must
        // still be a server this phone is signed in to.
        val signInDestination = if (target.isSignedOutTv) {
            val server = signInServer?.let { matched -> orderedServers.firstOrNull { it.id == matched.id } }
                ?: serverForSignedOutTv(target)
                ?: return fail(target, CompanionPairingText.Message(R.string.pairing_error_phoneNotSignedIn, listOf(target.name)))
            // The account chosen on the card, never a stand-in: one that has
            // since signed out or expired fails instead of signing the TV in
            // as someone else.
            val account = server.approvableAccounts.let { accounts ->
                if (signInAccountId != null) {
                    accounts.firstOrNull { it.id == signInAccountId }
                } else {
                    accounts.firstOrNull { it.isCurrent } ?: accounts.firstOrNull()
                }
            } ?: return fail(target, signInAgain(server))
            CompanionPairingDestination(server, account)
        } else {
            null
        }
        if (orderedServers.none { it.approvableAccounts.isNotEmpty() }) {
            return fail(target, CompanionPairingText.Message(R.string.pairing_error_noServers))
        }
        val choice = CompanionAccountChoice(
            targetName = target.name,
            servers = orderedServers,
            mode = target.selectionMode,
            limit = target.signInLimit,
        )

        val outcomes = mutableListOf<CompanionTargetOutcome>()
        var inFlight: TargetStep? = null
        var transport: PairingTransport? = null
        var reachedTv = false
        try {
            _status.value = CompanionPairingStatus.Connecting(target.name)
            Log.i(
                TAG,
                "Connecting to ${target.name} at ${target.host}:${target.port} " +
                    "(session=${target.sessionId ?: "unknown"})",
            )
            val connected = transportFactory.connect(target).also {
                transport = it
                reachedTv = true
            }
            coroutineScope {
                val inbox = Channel<PairingMessage>(Channel.UNLIMITED)
                val peer = PeerState()
                val collector = launch {
                    try {
                        connected.incoming.collect {
                            peer.observe(it)
                            inbox.send(it)
                        }
                    } finally {
                        // The TV hung up (or the read failed): wake any wait
                        // for its next message instead of sitting on it until
                        // the step's timeout.
                        peer.ended()
                        inbox.close()
                    }
                }
                try {
                    awaitCompatibleHello(inbox)
                    _status.value = CompanionPairingStatus.PickServers(target.name)
                    val selected = signInDestination?.let(::listOf) ?: choice.resolve(chooseAccounts(choice))
                    if (selected.isEmpty()) {
                        throw SessionEndedException(CompanionPairingText.Message(R.string.pairing_error_chooseAccount))
                    }

                    // The binding the phone trusts: from a request the person
                    // compared (contract §C.5). Kept when that target then fails.
                    val trust = BindingTrust()
                    selected.forEachIndexed { index, destination ->
                        val step = TargetStep(destination, position = index + 1, total = selected.size)
                        inFlight = step
                        val outcome = try {
                            pushAndApprove(
                                target = target,
                                step = step,
                                transport = connected,
                                inbox = inbox,
                                peer = peer,
                                trust = trust,
                                confirmMatch = confirmMatch,
                            )
                            CompanionTargetOutcome(destination.server.displayName, step.accountName)
                        } catch (failure: TargetFailedException) {
                            Log.w(TAG, "Companion target ${index + 1} of ${selected.size} failed: ${failure.text}")
                            CompanionTargetOutcome(destination.server.displayName, step.accountName, failure.text)
                        }
                        inFlight = null
                        outcomes += outcome
                        if (outcome.signedIn) {
                            _status.value = CompanionPairingStatus.SignedIn(
                                targetName = target.name,
                                serverName = destination.server.displayName,
                                serverCount = outcomes.count { it.signedIn },
                                accountName = step.accountName,
                            )
                        }
                    }
                    connected.send(PairingMessage.Done)
                } finally {
                    // The TLS input stream performs a blocking socket read. Cancelling its
                    // collector alone cannot interrupt that read, so close the transport
                    // before joining or successful pairing can remain stuck in cleanup.
                    withContext(NonCancellable) {
                        collector.cancel()
                        val toClose = transport
                        transport = null
                        withContext(Dispatchers.IO) { toClose?.close() }
                        collector.join()
                        inbox.close()
                    }
                }
            }
            return finish(target, outcomes)
        } catch (e: TimeoutCancellationException) {
            // A step timeout is a failure, not a cancellation of the caller:
            // rethrowing it left the card spinning on its progress step.
            Log.w(TAG, "Companion setup timed out for ${target.name}", e)
            return endedEarly(target, outcomes, inFlight, target.named(R.string.pairing_error_stoppedResponding))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Companion setup failed for ${target.name}", t)
            val message = when (t) {
                is PeerCancelledException -> if (t.reason == CONSENT_DENIED_REASON) {
                    target.named(R.string.pairing_error_notAllowed)
                } else {
                    target.named(R.string.pairing_error_cancelled)
                }
                is ClosedReceiveChannelException -> target.named(R.string.pairing_error_closed)
                else -> t.userFacingPairingMessage(target, reachedTv)
            }
            return endedEarly(target, outcomes, inFlight, message)
        } finally {
            val toClose = transport
            transport = null
            withContext(NonCancellable + Dispatchers.IO) {
                toClose?.close()
            }
        }
    }

    /** The session ran to its end: completed when anything signed in, else the failure(s). */
    private fun finish(target: CompanionPairingTarget, outcomes: List<CompanionTargetOutcome>): CompanionPairingResult {
        val signedIn = outcomes.count { it.signedIn }
        if (signedIn == 0) {
            val message = outcomes.singleOrNull()?.failure ?: CompanionPairingText.Lines(companionSummary(outcomes))
            return fail(target, message)
        }
        _status.value = CompanionPairingStatus.Completed(target.name, outcomes.toList())
        return CompanionPairingResult.Completed(signedIn, outcomes.filterNot { it.signedIn })
    }

    /**
     * The session ended before every target was sent. What signed in stays
     * signed in on the TV, so with any sign-in it is still a completed
     * session, and [message] is the in-flight target's failure.
     */
    private fun endedEarly(
        target: CompanionPairingTarget,
        outcomes: List<CompanionTargetOutcome>,
        inFlight: TargetStep?,
        message: CompanionPairingText,
    ): CompanionPairingResult {
        if (outcomes.none { it.signedIn }) return fail(target, message)
        val all = outcomes + listOfNotNull(
            inFlight?.let { CompanionTargetOutcome(it.server.displayName, it.accountName, message) },
        )
        return finish(target, all)
    }

    /** What the phone says when the TV reports [this] failure for [serverName]. */
    private fun PairingFailureCode.phoneMessage(target: CompanionPairingTarget, serverName: String): CompanionPairingText {
        val tvName = target.name
        return when (this) {
            PairingFailureCode.Denied -> CompanionPairingText.Message(R.string.pairing_failure_denied)
            PairingFailureCode.Expired -> target.named(R.string.pairing_failure_expired)
            PairingFailureCode.Unreachable -> CompanionPairingText.Message(
                if (target.isRoku) R.string.pairing_failure_unreachableRoku else R.string.pairing_failure_unreachableTv,
                listOf(tvName, serverName),
            )
            PairingFailureCode.IdentityMismatch -> target.named(R.string.pairing_failure_identityMismatch)
            PairingFailureCode.UpdateRequired ->
                CompanionPairingText.Message(R.string.pairing_failure_updateRequired, listOf(serverName, tvName))
            PairingFailureCode.AuthFailed ->
                CompanionPairingText.Message(R.string.pairing_failure_authFailed, listOf(tvName, serverName))
            PairingFailureCode.LimitReached -> target.named(R.string.pairing_failure_limitReached)
        }
    }

    /**
     * The card's text for a failure without its own case. This coordinator's
     * own failures (`error(...)`) are already written for the card; anything
     * else, such as a TLS or socket error, says what happened rather than
     * showing its raw text.
     */
    private fun Throwable.userFacingPairingMessage(target: CompanionPairingTarget, reachedTv: Boolean): CompanionPairingText {
        val own = message?.takeIf { this is IllegalStateException && this !is SiloAuthUnavailableException && it.isNotBlank() }
        return when {
            this is SessionEndedException -> text
            own != null -> CompanionPairingText.Verbatim(own)
            reachedTv -> target.named(R.string.pairing_error_connectionLost)
            else -> CompanionPairingText.Message(
                if (target.isRoku) R.string.pairing_error_couldntConnectRoku else R.string.pairing_error_couldntConnectTv,
            )
        }
    }

    /** One target being sent, and its place in the session. */
    private class TargetStep(val destination: CompanionPairingDestination, val position: Int, val total: Int) {
        val server: CompanionPairingServer get() = destination.server
        val isFirst: Boolean get() = position == 1

        /** Who the phone approves as: read from the server, else the saved username. */
        var accountName: String? = destination.account.username
    }

    /** The session binding the phone trusts, once a compared request supplied one. */
    private class BindingTrust {
        var binding: String? = null
    }

    /**
     * Pushes [step]'s server, checks the TV's request and approves it with
     * [step]'s account, updating [trust]. Throws [TargetFailedException] for
     * a failure that leaves the session usable; anything else ends the
     * session.
     */
    private suspend fun pushAndApprove(
        target: CompanionPairingTarget,
        step: TargetStep,
        transport: PairingTransport,
        inbox: Channel<PairingMessage>,
        peer: PeerState,
        trust: BindingTrust,
        confirmMatch: suspend (CompanionPairingApproval) -> Boolean,
    ) {
        val server = step.server
        val destination = step.destination

        // A signed-out TV keeps its sign-in request after it cancels this
        // attempt, so once it has ended the attempt (cancelled, hung up, or
        // reported this server failed) the phone must not approve or deny
        // that request. Checked before every decision.
        fun ensureAttemptLive() {
            peer.interruption(server.url)?.let { throw it.failure(target, server.displayName) }
        }

        // A result left over from the previous target (the same server can
        // come twice, once per account) must not read as this one's.
        if (!step.isFirst) {
            inbox.drainStale()
            peer.ended.value?.let { throw it.failure(target, server.displayName) }
        }
        peer.beginAttempt(server.url)

        // Read first: it renews this account's token for the lookup, and
        // the TV shows the name ("Signing in sam on Home").
        deviceLoginApprover.accountName(destination)?.let { step.accountName = it }
        _status.value = CompanionPairingStatus.PushingServer(
            target.name,
            server.displayName,
            step.accountName,
            step.position,
            step.total,
        )
        transport.send(
            PairingMessage.PushServer(
                serverURL = server.url,
                serverName = server.displayName,
                serverIdentity = identitySource?.identity(server),
                endpoints = identitySource?.endpoints(server),
                accountName = step.accountName,
            ),
        )

        // A TV can refuse the push before it starts a sign-in (a different
        // server at that address, an expired code, no room for more): it
        // answers with the failed result instead, and the phone moves on.
        val started = when (
            val first = inbox.receiveFirst<PairingMessage>(timeoutMs = deviceStartedTimeoutMs(step.isFirst)) {
                (it is PairingMessage.DeviceStarted && it.serverURL == server.url) ||
                    (it is PairingMessage.ServerResult && it.serverURL == server.url && it.status != PairingServerStatus.SignedIn)
            }
        ) {
            is PairingMessage.DeviceStarted -> first
            is PairingMessage.ServerResult -> targetFailed(PairingFailureCode.fromWire(first.error).phoneMessage(target, server.displayName))
            else -> throw SessionEndedException(CompanionPairingText.Message(R.string.pairing_error_unexpectedMessage))
        }
        val lookup = when (val result = deviceLoginApprover.lookup(destination, started.userCode)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> targetFailed(approverFailure(result, target, server, server.named(R.string.pairing_error_lookupFailed)))
            is ApiResult.NetworkError -> targetFailed(approverFailure(result, server))
        }
        ensureAttemptLive()
        val serverMatchCode = lookup.matchCode?.takeIf { it.isNotBlank() }
            ?: targetFailed(server.named(R.string.pairing_error_noMatchCode))
        // The TV relays the code pair the server gave it; a pair that doesn't
        // match the server's own isn't this request. Nothing is approved; the
        // TV withdraws the request when the next push (or done) supersedes it.
        if (serverMatchCode != started.matchCode) {
            targetFailed(CompanionPairingText.Message(R.string.pairing_error_codesDiffer))
        }

        val binding = lookup.binding?.takeIf { it.isNotBlank() }
        val trusted = trust.binding
        // A later request bound to another session isn't this TV's: refuse it.
        if (!step.isFirst && trusted != null && binding != null && binding != trusted) {
            targetFailed(PairingFailureCode.AuthFailed.phoneMessage(target, server.displayName))
        }
        val compare = step.isFirst || trusted == null || binding == null
        if (compare) {
            val approval = CompanionPairingApproval(
                targetName = target.name,
                serverName = server.displayName,
                userCode = lookup.userCode?.takeIf { it.isNotBlank() } ?: started.userCode,
                serverMatchCode = serverMatchCode,
                serverHost = DeviceCodeFormat.host(server.url),
                accountName = step.accountName,
                isFollowUp = !step.isFirst,
                position = step.position,
                total = step.total,
            )
            ensureAttemptLive()
            _status.value = CompanionPairingStatus.AwaitingMatchConfirmation(approval)
            // The TV can end the attempt while the person compares codes:
            // the card's buttons go away at once instead of acting on it.
            val confirmed = coroutineScope {
                val answer = async { confirmMatch(approval) }
                val interrupted = async { peer.awaitInterruption(server.url) }
                select<Unit> {
                    answer.onAwait {}
                    interrupted.onAwait {}
                }
                interrupted.cancel()
                // Also wins when both finished together; leaving this scope
                // by throwing cancels the pending answer.
                ensureAttemptLive()
                answer.await()
            }
            if (!confirmed) {
                // Leaving the scope above waits for the cancelled watcher,
                // and the TV can end the attempt in that time.
                ensureAttemptLive()
                // A code the person says doesn't match what the TV shows is the
                // strongest sign of someone else on the network: stop the whole
                // session, for a later account as for the first (same as Apple).
                deviceLoginApprover.deny(destination, started.userCode)
                transport.send(PairingMessage.Cancel(reason = "user-cancelled"))
                throw SessionEndedException(
                    CompanionPairingText.Message(
                        if (step.isFirst) R.string.pairing_error_declinedOnPhone else R.string.pairing_error_codesDifferStopped,
                    ),
                )
            }
            // A later target the person compared supplies the binding when
            // none is trusted yet; the first one does once it is approved.
            if (!step.isFirst && trust.binding == null) trust.binding = binding
        }

        ensureAttemptLive()
        _status.value = CompanionPairingStatus.Approving(
            target.name,
            server.displayName,
            step.accountName,
            step.position,
            step.total,
        )
        when (val approved = deviceLoginApprover.approve(destination, started.userCode)) {
            is ApiResult.Success -> Unit
            is ApiResult.Error -> targetFailed(approverFailure(approved, target, server, server.named(R.string.pairing_error_approveFailed)))
            is ApiResult.NetworkError -> targetFailed(approverFailure(approved, server))
        }
        if (step.isFirst && trust.binding == null) trust.binding = binding
        val result = inbox.receiveFirst<PairingMessage.ServerResult>(
            timeoutMs = SERVER_RESULT_TIMEOUT_MS,
        ) { it.serverURL == server.url }
        if (result.status != PairingServerStatus.SignedIn) {
            targetFailed(PairingFailureCode.fromWire(result.error).phoneMessage(target, server.displayName))
        }
    }

    private suspend fun awaitCompatibleHello(inbox: ReceiveChannel<PairingMessage>) {
        val hello = inbox.receiveFirst<PairingMessage.Hello>(timeoutMs = HELLO_TIMEOUT_MS)
        if (PairingProtocol.VERSION !in hello.supportedVersions) {
            throw SessionEndedException(CompanionPairingText.Message(R.string.pairing_error_incompatible))
        }
    }

    private suspend inline fun <reified T : PairingMessage> ReceiveChannel<PairingMessage>.receiveFirst(
        timeoutMs: Long,
        crossinline predicate: (T) -> Boolean = { true },
    ): T = withTimeout<T>(timeoutMs) {
        var found: T? = null
        while (found == null) {
            val message = receive()
            // The TV withdrew (its user declined, or it left the screen):
            // nothing it would have sent next is coming.
            if (message is PairingMessage.Cancel && T::class != PairingMessage.Cancel::class) {
                throw PeerCancelledException(message.reason)
            }
            if (message is T && predicate(message)) {
                found = message
            }
        }
        found
    }

    /** Drops what the TV already sent about earlier targets; a `cancel` among it still ends the session. */
    private fun ReceiveChannel<PairingMessage>.drainStale() {
        while (true) {
            val message = tryReceive().getOrNull() ?: return
            if (message is PairingMessage.Cancel) throw PeerCancelledException(message.reason)
        }
    }

    private fun CompanionPairingServerSnapshot.orderedServers(): List<CompanionPairingServer> {
        val activeId = activeServerId
        return servers
            .filter { it.url.isNotBlank() }
            .map { it.copy(isActive = it.id == activeId) }
            .sortedByDescending { it.isActive }
    }

    private fun fail(target: CompanionPairingTarget, message: CompanionPairingText): CompanionPairingResult.Failed {
        _status.value = CompanionPairingStatus.Failed(target.name, message)
        return CompanionPairingResult.Failed(message)
    }

    private fun deviceStartedTimeoutMs(isFirstServer: Boolean): Long =
        if (isFirstServer) FIRST_DEVICE_STARTED_TIMEOUT_MS else NEXT_DEVICE_STARTED_TIMEOUT_MS

    /** One target failed; the session goes on with the next. */
    private class TargetFailedException(val text: CompanionPairingText) : Exception()

    private fun targetFailed(text: CompanionPairingText): Nothing = throw TargetFailedException(text)

    /** The session ends with [text], written for the card. */
    private class SessionEndedException(val text: CompanionPairingText) : IllegalStateException()

    /** The TV sent `cancel` while the phone waited for its next message. */
    private class PeerCancelledException(val reason: String) : IllegalStateException("peer cancelled: $reason")

    /** What ended the TV's side of a server's attempt. */
    private sealed class PeerInterruption {
        data class Cancelled(val reason: String) : PeerInterruption()
        data object Closed : PeerInterruption()
        data class Failed(val error: String?) : PeerInterruption()
    }

    /**
     * Records, as the TV's messages arrive, anything that ends an attempt: a
     * `cancel`, the connection closing, or a failed result for a server. The
     * inbox only shows these when the phone next reads it, which is after
     * the decision it would have stopped.
     */
    private class PeerState {
        val ended = MutableStateFlow<PeerInterruption?>(null)
        private val failed = MutableStateFlow<Map<String, PeerInterruption.Failed>>(emptyMap())

        fun observe(message: PairingMessage) {
            when (message) {
                is PairingMessage.Cancel -> ended.compareAndSet(null, PeerInterruption.Cancelled(message.reason))
                is PairingMessage.ServerResult -> if (message.status != PairingServerStatus.SignedIn) {
                    failed.update { it + (message.serverURL to PeerInterruption.Failed(message.error)) }
                }
                else -> Unit
            }
        }

        fun ended() {
            ended.compareAndSet(null, PeerInterruption.Closed)
        }

        /** A new attempt for [serverURL]: an earlier attempt's failure there is not this one's. */
        fun beginAttempt(serverURL: String) {
            failed.update { it - serverURL }
        }

        /** Why [serverURL]'s attempt is over, or null while it is live. */
        fun interruption(serverURL: String): PeerInterruption? = ended.value ?: failed.value[serverURL]

        suspend fun awaitInterruption(serverURL: String): PeerInterruption =
            combine(ended, failed) { end, failures -> end ?: failures[serverURL] }.filterNotNull().first()
    }

    /**
     * The failure the phone reports for [this], phrased as when it's read
     * from the inbox. The TV reporting this attempt failed leaves the
     * session usable; a cancel or hang-up ends it.
     */
    private fun PeerInterruption.failure(target: CompanionPairingTarget, serverName: String): Throwable = when (this) {
        is PeerInterruption.Cancelled -> PeerCancelledException(reason)
        PeerInterruption.Closed -> ClosedReceiveChannelException("${target.name} closed the connection")
        is PeerInterruption.Failed -> TargetFailedException(PairingFailureCode.fromWire(error).phoneMessage(target, serverName))
    }

    private companion object {
        const val TAG = "CompanionPairing"
        /** [PairingReceiver.denyPendingServer]'s reason when the TV user declines the push. */
        const val CONSENT_DENIED_REASON = "consent_denied"
        const val HELLO_TIMEOUT_MS = 15_000L
        const val FIRST_DEVICE_STARTED_TIMEOUT_MS = 90_000L
        const val NEXT_DEVICE_STARTED_TIMEOUT_MS = 30_000L
        const val SERVER_RESULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * The phone's text for a code lookup or approval that failed, worded like
 * the iPhone app's. The server's own error text is never shown. The sign-in
 * provider being unreachable (503 `provider_unavailable` on the approver's
 * refresh or answer) keeps this phone's session, so it says so.
 */
internal fun approverFailure(
    result: ApiResult.Error,
    target: CompanionPairingTarget,
    server: CompanionPairingServer,
    otherwise: CompanionPairingText,
): CompanionPairingText = when {
    // The app's own update notice (no request was sent): retrying can't help.
    result.code == 0 && result.error == ApiV2Gate.UPDATE_REQUIRED_ERROR -> CompanionPairingText.Verbatim(result.message)
    result.code == 503 && result.error == SiloAuthUnavailableException.PROVIDER_UNAVAILABLE_PROBLEM ->
        APPROVER_PROVIDER_UNAVAILABLE
    result.code == 401 -> signInAgain(server)
    // Approved, denied, or cancelled on the TV already.
    result.code == 409 -> target.named(R.string.pairing_error_alreadyDecided)
    result.code == 404 || result.code == 410 -> target.named(R.string.pairing_error_codeExpired)
    else -> otherwise
}

internal fun approverFailure(result: ApiResult.NetworkError, server: CompanionPairingServer): CompanionPairingText = when {
    SiloAuthUnavailableException.isProviderUnavailable(result.exception) -> APPROVER_PROVIDER_UNAVAILABLE
    result.exception is SiloAuthUnavailableException -> signInAgain(server)
    else -> server.named(R.string.pairing_error_serverUnreachable)
}

private fun signInAgain(server: CompanionPairingServer) = server.named(R.string.pairing_error_signInAgain)

/** [id] with the TV's name as its one placeholder. */
private fun CompanionPairingTarget.named(@StringRes id: Int) = CompanionPairingText.Message(id, listOf(name))

/** [id] with the server's name as its one placeholder. */
private fun CompanionPairingServer.named(@StringRes id: Int) = CompanionPairingText.Message(id, listOf(displayName))

internal val APPROVER_PROVIDER_UNAVAILABLE = CompanionPairingText.Message(R.string.signInTv_error_providerUnavailable)
