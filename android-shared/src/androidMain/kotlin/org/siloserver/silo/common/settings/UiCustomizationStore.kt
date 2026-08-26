package org.siloserver.silo.common.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.NavigationShortcuts
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SiloClientFamily
import org.siloserver.silo.model.settings.UiCustomizationCodec
import org.siloserver.silo.model.settings.supportsUiCustomization
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.newSettingMutationId
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository

/**
 * Cached, server-synchronized navigation and card preferences.
 *
 * Family-scoped values are shared by Android and Apple devices in the same
 * family through `profile_client`; shortcuts remain profile-wide. Every edit
 * is cached before its best-effort network write. A failed write is marked
 * pending and retried on the next foreground refresh, so airplane mode and an
 * older server never make the app forget a usable local layout.
 */
interface UiCustomizationStore {
    val family: SiloClientFamily
    /** true=supported, false=confirmed incompatible, null=unknown/transiently offline. */
    val uiCustomizationSupported: StateFlow<Boolean?>
    val primaryMenu: StateFlow<PrimaryMenu?>
    val shortcuts: StateFlow<NavigationShortcuts>
    val cardPresentation: StateFlow<CardPresentation>
    /** Raw effective source scope, retained for forward-compatible UI. */
    val primaryMenuSource: StateFlow<String?>
    val cardPresentationSource: StateFlow<String?>

    suspend fun refresh()
    fun setPrimaryMenu(value: PrimaryMenu)
    fun updatePrimaryMenu(
        fallback: PrimaryMenu,
        transform: (PrimaryMenu) -> PrimaryMenu,
    )
    fun resetPrimaryMenu()
    fun setShortcutPresent(item: PrimaryMenuItem, present: Boolean)
    /** Durably author both halves of one menu pin/unpin before either request starts. */
    fun setPrimaryMenuAndShortcut(
        value: PrimaryMenu,
        item: PrimaryMenuItem,
        present: Boolean,
    )
    fun updatePrimaryMenuAndShortcut(
        fallback: PrimaryMenu,
        item: PrimaryMenuItem,
        present: Boolean,
        /** `null` rejects both halves of the compound mutation atomically. */
        transform: (PrimaryMenu) -> PrimaryMenu?,
    )
    fun setCardPresentation(value: CardPresentation)
    fun updateCardPresentation(transform: (CardPresentation) -> CardPresentation)
    /** Clear higher-precedence device rows and inherit this family's values. */
    fun useFamilySettings()
    /**
     * Synchronously crosses a runtime client-family boundary, invalidating
     * queued authoring work and painting the new family's durable cache.
     */
    fun reclassifyClientFamily()
    fun clear()
}

class DefaultUiCustomizationStore(
    family: SiloClientFamily,
    private val repository: SettingsRepository,
    private val tokenManager: TokenManager,
    private val cache: AndroidServerSettingsCache,
    private val scope: CoroutineScope,
    private val identityTransitions: IdentityTransitionBarrier =
        DefaultIdentityTransitionBarrier(),
    private val familyProvider: () -> SiloClientFamily = { family },
) : UiCustomizationStore {
    override val family: SiloClientFamily
        get() = familyProvider()

    private val _uiCustomizationSupported = MutableStateFlow<Boolean?>(null)
    private val _primaryMenu = MutableStateFlow<PrimaryMenu?>(null)
    private val _shortcuts = MutableStateFlow(NavigationShortcuts.EMPTY)
    private val _cardPresentation = MutableStateFlow(CardPresentation.DEFAULT)
    private val _primaryMenuSource = MutableStateFlow<String?>(null)
    private val _cardPresentationSource = MutableStateFlow<String?>(null)

    override val uiCustomizationSupported: StateFlow<Boolean?> =
        _uiCustomizationSupported.asStateFlow()
    override val primaryMenu: StateFlow<PrimaryMenu?> = _primaryMenu.asStateFlow()
    override val shortcuts: StateFlow<NavigationShortcuts> = _shortcuts.asStateFlow()
    override val cardPresentation: StateFlow<CardPresentation> =
        _cardPresentation.asStateFlow()
    override val primaryMenuSource: StateFlow<String?> = _primaryMenuSource.asStateFlow()
    override val cardPresentationSource: StateFlow<String?> =
        _cardPresentationSource.asStateFlow()

    private val refreshMutex = Mutex()
    private val mutationMutex = Mutex()
    private val authoringMutex = Mutex()
    private val authoringStateLock = Any()
    private val generationLock = Any()
    private val navigationOutboxLock = Any()
    private val authoringCommands = Channel<AuthoringCommand>(Channel.UNLIMITED)
    private val generations = mutableMapOf<String, Long>()
    private var authoringEpoch = 0L
    private var observedFamily = family

    @Volatile
    private var activeIdentity: CacheIdentity? = null

    init {
        // Clear synchronously at the privacy boundary, before the new identity
        // becomes visible. Registry-derived UI keys cannot see temporary cast
        // scopes, so the store owns this complete identity lifecycle itself.
        identityTransitions.installGate { transition ->
            if (transition.phase == IdentityTransitionPhase.WILL_CHANGE) clear()
        }
        // Start undispatched so no transition can fall between construction and
        // SharedFlow subscription. DID_CHANGE always resolves one atomic auth
        // snapshot before activating and refreshing the replacement owner.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            identityTransitions.transitions.collect { transition ->
                if (transition.phase == IdentityTransitionPhase.DID_CHANGE) refresh()
            }
        }
        scope.launch {
            for (command in authoringCommands) {
                executeAuthoringCommand(command)
            }
        }
    }

    override suspend fun refresh() {
        reclassifyClientFamily()
        val requestedEpoch = synchronized(authoringStateLock) { authoringEpoch }
        // Identity activation is deliberately outside the serialized network
        // lane. If server A has a slow GET in flight, switching to server B
        // must paint B's cache/defaults immediately instead of leaving A's
        // presentation visible until that request times out.
        val binding = authoringMutex.withLock author@{
            val current = currentBinding() ?: return@author null
            synchronized(authoringStateLock) {
                if (requestedEpoch != authoringEpoch) {
                    null
                } else {
                    activateIdentity(current.identity)
                    current
                }
            }
        } ?: return

        refreshMutex.withLock {
            // This particular refresh belongs to the identity and clear epoch
            // captured above. A later lifecycle refresh owns any newer scope.
            if (synchronized(authoringStateLock) { requestedEpoch != authoringEpoch } ||
                activeIdentity != binding.identity || !currentOwnerMatches(binding)
            ) return@withLock
            val support = when (val result = repository.contractCapabilities()) {
                is SettingsCapabilitiesResult.Available ->
                    result.capabilities.supportsUiCustomization
                SettingsCapabilitiesResult.ServerUpgradeRequired -> false
                is SettingsCapabilitiesResult.Error,
                is SettingsCapabilitiesResult.NetworkError -> null
            }
            if (synchronized(authoringStateLock) { requestedEpoch != authoringEpoch } ||
                activeIdentity != binding.identity || !currentOwnerMatches(binding)
            ) return@withLock
            _uiCustomizationSupported.value = support
            // A confirmed old/partial server cannot accept this contract. A
            // transiently unknown server keeps cached presentation for offline
            // use, but avoids speculative revision-5 writes until re-probed.
            if (support == true) refreshBinding(binding, requestedEpoch)
        }
    }

    private suspend fun refreshBinding(binding: RequestBinding, requestedEpoch: Long) {
        val identity = binding.identity

        val pendingDeviceDeletes = flushPendingDeviceDeletes(binding)
        val pending = flushPending(binding)
        val shortcutOpsPending = flushPendingShortcutOps(binding)
        if (synchronized(authoringStateLock) { requestedEpoch != authoringEpoch } ||
            activeIdentity != identity || !currentOwnerMatches(binding)
        ) return
        // A GET can begin before a local edit, then return after that edit's
        // PUT/DELETE has already cleared its pending marker. Pending state
        // alone therefore cannot identify a stale response: retain the
        // per-key generations that this request was made against as well.
        val requestedGenerations = snapshotGenerations(ALL_KEYS)
        when (
            val result = repository.getEffectiveValues(
                listOf(
                    SettingKeys.NAV_PRIMARY_MENU,
                    SettingKeys.NAV_SHORTCUTS,
                    SettingKeys.UI_CARD_PRESENTATION,
                ),
                profileId = identity.profileId,
                authScope = binding.authScope,
            )
        ) {
            is ApiResult.Success -> {
                // A logout/server switch can clear this singleton while the
                // request is in flight. Never publish the previous profile's
                // response into the newly active UI.
                if (synchronized(authoringStateLock) { requestedEpoch != authoringEpoch } ||
                    activeIdentity != identity || !currentOwnerMatches(binding)
                ) return
                // Authoring and response application share one short critical
                // section. A local edit can therefore only happen entirely
                // before this generation check (and make it fail), or after
                // this paint (and overwrite it with the optimistic value).
                synchronized(authoringStateLock) {
                    if (activeIdentity != identity) return@synchronized
                    if (SettingKeys.NAV_PRIMARY_MENU !in pending &&
                        SettingKeys.NAV_PRIMARY_MENU !in pendingDeviceDeletes &&
                        !isPending(identity, SettingKeys.NAV_PRIMARY_MENU)
                        && !isPendingDeviceDelete(identity, SettingKeys.NAV_PRIMARY_MENU)
                        && isCurrent(
                            SettingKeys.NAV_PRIMARY_MENU,
                            requestedGenerations.getValue(SettingKeys.NAV_PRIMARY_MENU),
                        )
                    ) {
                        result.data[SettingKeys.NAV_PRIMARY_MENU]?.let { row ->
                            val value = row.value
                            if (value is JsonNull) {
                                _primaryMenu.value = null
                                cacheValue(identity, SettingKeys.NAV_PRIMARY_MENU, JsonNull)
                            } else {
                                UiCustomizationCodec.parsePrimaryMenu(value)?.let { parsed ->
                                    _primaryMenu.value = parsed
                                    cacheValue(identity, SettingKeys.NAV_PRIMARY_MENU, value)
                                }
                            }
                            effectiveSource(row.source, row.scope).let { source ->
                                _primaryMenuSource.value = source
                                cacheSource(identity, SettingKeys.NAV_PRIMARY_MENU, source)
                            }
                        }
                    }
                    if (!shortcutOpsPending &&
                        !hasPendingShortcutOps(identity) &&
                        isCurrent(
                            SettingKeys.NAV_SHORTCUTS,
                            requestedGenerations.getValue(SettingKeys.NAV_SHORTCUTS),
                        )
                    ) {
                        result.data[SettingKeys.NAV_SHORTCUTS]?.value?.let { value ->
                            UiCustomizationCodec.parseShortcuts(value)?.let { parsed ->
                                _shortcuts.value = parsed
                                cacheValue(identity, SettingKeys.NAV_SHORTCUTS, value)
                            }
                        }
                    }
                    if (SettingKeys.UI_CARD_PRESENTATION !in pending &&
                        SettingKeys.UI_CARD_PRESENTATION !in pendingDeviceDeletes &&
                        !isPending(identity, SettingKeys.UI_CARD_PRESENTATION)
                        && !isPendingDeviceDelete(identity, SettingKeys.UI_CARD_PRESENTATION)
                        && isCurrent(
                            SettingKeys.UI_CARD_PRESENTATION,
                            requestedGenerations.getValue(SettingKeys.UI_CARD_PRESENTATION),
                        )
                    ) {
                        result.data[SettingKeys.UI_CARD_PRESENTATION]?.let { row ->
                            val value = row.value
                            UiCustomizationCodec.parseCardPresentation(value)?.let { parsed ->
                                _cardPresentation.value = parsed
                                cacheValue(identity, SettingKeys.UI_CARD_PRESENTATION, value)
                            }
                            effectiveSource(row.source, row.scope).let { source ->
                                _cardPresentationSource.value = source
                                cacheSource(identity, SettingKeys.UI_CARD_PRESENTATION, source)
                            }
                        }
                    }
                }
            }
            is ApiResult.Error, is ApiResult.NetworkError -> Unit
        }
    }

    override fun setPrimaryMenu(value: PrimaryMenu) {
        authorPrimaryMenu { value }
    }

    override fun updatePrimaryMenu(
        fallback: PrimaryMenu,
        transform: (PrimaryMenu) -> PrimaryMenu,
    ) {
        authorPrimaryMenu { current -> transform(current ?: fallback) }
    }

    private fun authorPrimaryMenu(resolve: (PrimaryMenu?) -> PrimaryMenu) {
        authorMutation { binding ->
            val identity = binding.identity
            val value = resolve(_primaryMenu.value)
            val encoded = UiCustomizationCodec.encodePrimaryMenu(value)
            if (UiCustomizationCodec.parsePrimaryMenu(encoded) == null) return@authorMutation
            enqueue(
                binding = binding,
                key = SettingKeys.NAV_PRIMARY_MENU,
                value = encoded,
            )
            _primaryMenu.value = value
            setFamilySourceUnlessDeviceOverride(
                identity = identity,
                key = SettingKeys.NAV_PRIMARY_MENU,
                source = _primaryMenuSource,
            )
        }
    }

    override fun resetPrimaryMenu() {
        authorMutation { binding ->
            val identity = binding.identity
            cachePendingMutation(
                identity,
                SettingKeys.NAV_PRIMARY_MENU,
                PendingMutation(JsonNull, mutationId = null),
            )
            cacheValue(identity, SettingKeys.NAV_PRIMARY_MENU, JsonNull)
            cacheSource(identity, SettingKeys.NAV_PRIMARY_MENU, null)
            markPending(identity, SettingKeys.NAV_PRIMARY_MENU, true)
            val generation = nextGeneration(SettingKeys.NAV_PRIMARY_MENU)
            _primaryMenu.value = null
            _primaryMenuSource.value = null
            scope.launch {
                mutationMutex.withLock {
                    if (!isCurrent(SettingKeys.NAV_PRIMARY_MENU, generation) ||
                        activeIdentity != identity || !currentOwnerMatches(binding)
                    ) return@withLock
                    val result = repository.clearProfileClientValue(
                            SettingKeys.NAV_PRIMARY_MENU,
                            profileId = identity.profileId,
                            authScope = binding.authScope,
                            clientFamily = identity.family,
                        )
                    val identityStillCurrent = currentOwnerMatches(binding)
                    if (result is ApiResult.Success && identityStillCurrent) {
                        synchronized(authoringStateLock) {
                            if (isCurrent(SettingKeys.NAV_PRIMARY_MENU, generation) &&
                                activeIdentity == identity
                            ) {
                                markPending(identity, SettingKeys.NAV_PRIMARY_MENU, false)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun setShortcutPresent(item: PrimaryMenuItem, present: Boolean) {
        if (UiCustomizationCodec.encodeShortcutItem(item) == null) return
        authorMutation { binding ->
            val identity = binding.identity
            val operation = PendingShortcutOperation(
                item = item,
                present = present,
                mutationId = newSettingMutationId(),
            )
            val accepted = synchronized(navigationOutboxLock) {
                val optimistic = applyShortcutOperation(_shortcuts.value, operation)
                val encoded = UiCustomizationCodec.encodeShortcuts(optimistic)
                if (UiCustomizationCodec.parseShortcuts(encoded) == null) {
                    false
                } else {
                    // The outbox is the durable source of intent; write it before
                    // painting the optimistic document so a process death can
                    // reconstruct the same membership change from trusted cache.
                    val semanticIdentity = UiCustomizationCodec.identity(operation.item)
                    val state = readPendingNavigationState(identity)
                    val next = state.shortcutOperations.filterNot {
                        UiCustomizationCodec.identity(it.item) == semanticIdentity
                    } + operation
                    writePendingNavigationState(
                        identity,
                        state.copy(shortcutOperations = next),
                    )
                    nextGeneration(SettingKeys.NAV_SHORTCUTS)
                    _shortcuts.value = optimistic
                    cacheValue(identity, SettingKeys.NAV_SHORTCUTS, encoded)
                    true
                }
            }
            if (accepted) scope.launch { flushPendingShortcutOps(binding) }
        }
    }

    override fun setPrimaryMenuAndShortcut(
        value: PrimaryMenu,
        item: PrimaryMenuItem,
        present: Boolean,
    ) {
        authorPrimaryMenuAndShortcut(item, present) { value }
    }

    override fun updatePrimaryMenuAndShortcut(
        fallback: PrimaryMenu,
        item: PrimaryMenuItem,
        present: Boolean,
        transform: (PrimaryMenu) -> PrimaryMenu?,
    ) {
        authorPrimaryMenuAndShortcut(item, present) { current ->
            transform(current ?: fallback)
        }
    }

    private fun authorPrimaryMenuAndShortcut(
        item: PrimaryMenuItem,
        present: Boolean,
        resolve: (PrimaryMenu?) -> PrimaryMenu?,
    ) {
        authorMutation { binding ->
            val identity = binding.identity
            val value = resolve(_primaryMenu.value) ?: return@authorMutation
            val encodedMenu = UiCustomizationCodec.encodePrimaryMenu(value)
            if (UiCustomizationCodec.parsePrimaryMenu(encodedMenu) == null ||
                UiCustomizationCodec.encodeShortcutItem(item) == null
            ) return@authorMutation
            val menuMutation = PendingMutation(encodedMenu, newSettingMutationId())
            val shortcutOperation = PendingShortcutOperation(
                item = item,
                present = present,
                mutationId = newSettingMutationId(),
            )
            val accepted = synchronized(navigationOutboxLock) {
                val optimisticShortcuts = applyShortcutOperation(
                    _shortcuts.value,
                    shortcutOperation,
                )
                val encodedShortcuts = UiCustomizationCodec.encodeShortcuts(optimisticShortcuts)
                if (UiCustomizationCodec.parseShortcuts(encodedShortcuts) == null) {
                    false
                } else {
                    val semanticIdentity = UiCustomizationCodec.identity(item)
                    val state = readPendingNavigationState(identity)
                    val nextShortcuts = state.shortcutOperations.filterNot {
                        UiCustomizationCodec.identity(it.item) == semanticIdentity
                    } + shortcutOperation
                    // One cache record is the transaction boundary: after
                    // this write, a restart can always finish both stable-id
                    // substeps, even if the process dies before either UI
                    // cache write or request begins.
                    writePendingNavigationState(
                        identity,
                        state.copy(
                            menuMutations = state.menuMutations +
                                (identity.family.wire to menuMutation),
                            shortcutOperations = nextShortcuts,
                        ),
                    )
                    markPendingFlag(identity, SettingKeys.NAV_PRIMARY_MENU, true)
                    nextGeneration(SettingKeys.NAV_PRIMARY_MENU)
                    nextGeneration(SettingKeys.NAV_SHORTCUTS)
                    cacheValue(identity, SettingKeys.NAV_PRIMARY_MENU, encodedMenu)
                    cacheValue(identity, SettingKeys.NAV_SHORTCUTS, encodedShortcuts)
                    _primaryMenu.value = value
                    _shortcuts.value = optimisticShortcuts
                    true
                }
            }
            if (!accepted) return@authorMutation
            setFamilySourceUnlessDeviceOverride(
                identity = identity,
                key = SettingKeys.NAV_PRIMARY_MENU,
                source = _primaryMenuSource,
            )
            scope.launch {
                flushPending(binding)
                flushPendingShortcutOps(binding)
            }
        }
    }

    override fun setCardPresentation(value: CardPresentation) {
        authorCardPresentation { value }
    }

    override fun updateCardPresentation(
        transform: (CardPresentation) -> CardPresentation,
    ) {
        authorCardPresentation(transform)
    }

    private fun authorCardPresentation(
        resolve: (CardPresentation) -> CardPresentation,
    ) {
        authorMutation { binding ->
            val identity = binding.identity
            val value = resolve(_cardPresentation.value)
            val encoded = UiCustomizationCodec.encodeCardPresentation(value)
            if (UiCustomizationCodec.parseCardPresentation(encoded) == null) {
                return@authorMutation
            }
            enqueue(
                binding = binding,
                key = SettingKeys.UI_CARD_PRESENTATION,
                value = encoded,
            )
            _cardPresentation.value = value
            setFamilySourceUnlessDeviceOverride(
                identity = identity,
                key = SettingKeys.UI_CARD_PRESENTATION,
                source = _cardPresentationSource,
            )
        }
    }

    override fun useFamilySettings() {
        authorMutation { binding ->
            val identity = binding.identity
            val keys = buildList {
                if (_primaryMenuSource.value == SettingScope.PROFILE_DEVICE.wire) {
                    add(SettingKeys.NAV_PRIMARY_MENU)
                }
                if (_cardPresentationSource.value == SettingScope.PROFILE_DEVICE.wire) {
                    add(SettingKeys.UI_CARD_PRESENTATION)
                }
            }
            if (keys.isEmpty()) return@authorMutation
            // Persist each idempotent device-row delete before starting I/O. A
            // partial failure or process death must not strand the remaining
            // higher-precedence row forever.
            keys.forEach { key ->
                markPendingDeviceDelete(identity, key, true)
                nextGeneration(key)
            }
            scope.launch { refresh() }
        }
    }

    override fun clear() {
        synchronized(authoringStateLock) {
            authoringEpoch += 1
            activeIdentity = null
            resetVisibleState()
            invalidateAllGenerations()
        }
    }

    override fun reclassifyClientFamily() {
        val nextFamily = familyProvider()
        synchronized(authoringStateLock) {
            val currentIdentity = activeIdentity
            if (
                observedFamily == nextFamily &&
                (currentIdentity == null || currentIdentity.family == nextFamily)
            ) {
                return@synchronized
            }

            observedFamily = nextFamily
            authoringEpoch += 1
            invalidateAllGenerations()
            if (currentIdentity == null) {
                resetVisibleState()
            } else {
                // Cache keys and pending menu outbox entries are family-scoped.
                // Re-key from the stable auth identity without waiting for I/O.
                activateIdentity(currentIdentity.copy(family = nextFamily))
            }
        }
    }

    private fun resetVisibleState() {
        _uiCustomizationSupported.value = null
        _primaryMenu.value = null
        _shortcuts.value = NavigationShortcuts.EMPTY
        _cardPresentation.value = CardPresentation.DEFAULT
        _primaryMenuSource.value = null
        _cardPresentationSource.value = null
    }

    private fun invalidateAllGenerations() {
        synchronized(generationLock) {
            ALL_KEYS.forEach { key ->
                generations[key] = (generations[key] ?: 0) + 1
            }
        }
    }

    private fun enqueue(binding: RequestBinding, key: String, value: JsonElement) {
        val identity = binding.identity
        val mutationId = newSettingMutationId()
        val pendingMutation = PendingMutation(value, mutationId)
        // Persist value + id as one atomic record before the async request can
        // start. A process death can never pair a newer value with an older
        // idempotency receipt.
        cachePendingMutation(identity, key, pendingMutation)
        cacheValue(identity, key, value)
        markPending(identity, key, true)
        val generation = nextGeneration(key)
        scope.launch {
            mutationMutex.withLock {
                if (!isCurrent(key, generation) || activeIdentity != identity ||
                    !currentOwnerMatches(binding)
                ) return@withLock
                val result = repository.setProfileClientValue(
                    key,
                    value,
                    mutationId = mutationId,
                    profileId = identity.profileId,
                    authScope = binding.authScope,
                    clientFamily = identity.family,
                )
                val identityStillCurrent = currentOwnerMatches(binding)
                if (result is ApiResult.Success && identityStillCurrent) {
                    synchronized(authoringStateLock) {
                        if (activeIdentity == identity && isCurrent(key, generation) &&
                            cachedPendingMutation(identity, key) == pendingMutation
                        ) {
                            markPending(identity, key, false)
                        }
                    }
                }
            }
        }
    }

    /** Returns keys that still have a local write the server has not accepted. */
    private suspend fun flushPending(binding: RequestBinding): Set<String> =
        mutationMutex.withLock {
            val identity = binding.identity
            val stillPending = mutableSetOf<String>()
            for (key in PENDING_VALUE_KEYS) {
                if (!isPending(identity, key)) continue
                if (activeIdentity != identity || !currentOwnerMatches(binding)) {
                    stillPending += key
                    continue
                }
                val persisted = cachedPendingMutation(identity, key)
                val value = persisted?.value ?: cachedJson(identity, key)
                val mutationId = if (value != null && value !is JsonNull) {
                    persisted?.mutationId ?: cachedLegacyMutationId(identity, key)
                        ?: newSettingMutationId().also { generated ->
                        // Upgrade pending writes created by an older app build
                        // exactly once, then retain the id for every retry.
                        cachePendingMutation(
                            identity,
                            key,
                            PendingMutation(value, generated),
                        )
                    }
                } else {
                    null
                }
                val sent = PendingMutation(value ?: JsonNull, mutationId)
                if (persisted == null) cachePendingMutation(identity, key, sent)
                val result = when {
                    key == SettingKeys.NAV_PRIMARY_MENU && value is JsonNull ->
                        repository.clearProfileClientValue(
                            key,
                            profileId = identity.profileId,
                            authScope = binding.authScope,
                            clientFamily = identity.family,
                        )
                    value == null -> null
                    else -> repository.setProfileClientValue(
                        key,
                        value,
                        mutationId = checkNotNull(mutationId),
                        profileId = identity.profileId,
                        authScope = binding.authScope,
                        clientFamily = identity.family,
                    )
                }
                // A picker can author a newer cached value while this retry is in
                // flight. Only clear the pending bit when the successful request
                // still represents the cache contents we just sent.
                val identityStillCurrent = currentOwnerMatches(binding)
                val accepted = result is ApiResult.Success && identityStillCurrent &&
                    synchronized(authoringStateLock) {
                        if (activeIdentity != identity ||
                            cachedPendingMutation(identity, key) != sent
                        ) {
                            false
                        } else {
                            // The atomic pending record is authoritative.
                            // Repaint the ordinary cache here as well, covering
                            // a process death between its durable write and the
                            // original display-cache write.
                            cacheValue(identity, key, sent.value)
                            markPending(identity, key, false)
                            true
                        }
                    }
                if (!accepted) {
                    stillPending += key
                }
            }
            stillPending
        }

    /** Drains atomic shortcut membership operations in authored order. */
    private suspend fun flushPendingShortcutOps(binding: RequestBinding): Boolean =
        mutationMutex.withLock {
            val identity = binding.identity
            var couldNotDrain = false
            var rejectedAnyOperation = false
            // One id rotation per item per pass. The rotated id is durable, so
            // a genuinely fresh id cannot collide again; a second conflict in
            // the same pass is treated as retryable rather than spun on.
            val rotatedIdentities = mutableSetOf<String>()
            drain@ while (true) {
                val operation = pendingShortcutOperations(identity).firstOrNull()
                    ?: break
                if (activeIdentity != identity || !currentOwnerMatches(binding)) {
                    couldNotDrain = true
                    break
                }
                when (
                    val result = repository.setNavigationShortcutPresent(
                        item = operation.item,
                        present = operation.present,
                        mutationId = operation.mutationId,
                        profileId = identity.profileId,
                        authScope = binding.authScope,
                    )
                ) {
                    is ApiResult.Success -> {
                        // The request may have completed after sign-out or a
                        // server/profile switch. Keep the durable operation
                        // for an idempotent retry, but never repaint another
                        // identity with this response.
                        if (activeIdentity != identity || !currentOwnerMatches(binding)) {
                            couldNotDrain = true
                            break@drain
                        }
                        if (!acceptShortcutOperationSuccess(identity, operation, result.data.value)) {
                            couldNotDrain = true
                            break@drain
                        }
                    }
                    is ApiResult.Error -> {
                        val semanticIdentity = UiCustomizationCodec.identity(operation.item)
                        if (result.code == HTTP_CONFLICT) {
                            // The id, not the intent, is what this refuses:
                            // the server already recorded different content
                            // under it, so every replay of the same id fails
                            // identically and would wedge the whole outbox.
                            // Desired-presence writes are declarative, so
                            // re-issuing this operation under a fresh id
                            // cannot double-apply anything.
                            if (rotatedIdentities.add(semanticIdentity)) {
                                shortcutFailed(
                                    operation,
                                    "${result.code} ${result.error}: ${result.message}",
                                    disposition = "retried with a new mutation id",
                                )
                                if (!rotateShortcutMutationId(identity, operation)) {
                                    couldNotDrain = true
                                    break@drain
                                }
                                continue@drain
                            }
                            // A freshly minted id conflicted as well: some
                            // other writer owns it. Retry later rather than
                            // spinning here or discarding the user's intent.
                            shortcutFailed(
                                operation,
                                "${result.code} ${result.error}: ${result.message}",
                                disposition = "kept queued for retry",
                            )
                            couldNotDrain = true
                            break@drain
                        }
                        if (isRetryableHttp(result.code)) {
                            shortcutFailed(
                                operation,
                                "${result.code} ${result.error}: ${result.message}",
                                disposition = "kept queued for retry",
                            )
                            couldNotDrain = true
                            break@drain
                        }
                        // Definitive: this operation can never land as
                        // authored (deleted library, malformed item, a
                        // validation rule the server has since tightened).
                        // Retrying it forever would block every later pin and
                        // keep the local document from ever reconciling.
                        shortcutFailed(
                            operation,
                            "${result.code} ${result.error}: ${result.message}",
                            disposition = "dropped",
                        )
                        if (!rejectShortcutOperation(identity, operation)) {
                            couldNotDrain = true
                            break@drain
                        }
                        rejectedAnyOperation = true
                    }
                    is ApiResult.NetworkError -> {
                        shortcutFailed(
                            operation,
                            "network error: ${result.exception}",
                            disposition = "kept queued for retry",
                        )
                        couldNotDrain = true
                        break@drain
                    }
                }
            }
            // A dropped operation leaves the local document holding an intent
            // the server never accepted. Its pending marker is gone now, so a
            // fetch can finally adopt the authoritative shortcut document.
            if (rejectedAnyOperation) scope.launch { refresh() }
            couldNotDrain
        }

    /**
     * Drops a definitively rejected operation and reverts only its own
     * optimistic effect, leaving every sibling operation queued and painted.
     *
     * Returns false when the identity moved on mid-request, in which case the
     * durable operation is left untouched for the owner that authored it.
     */
    private fun rejectShortcutOperation(
        identity: CacheIdentity,
        operation: PendingShortcutOperation,
    ): Boolean {
        synchronized(authoringStateLock) {
            if (activeIdentity != identity) return false
            synchronized(navigationOutboxLock) {
                val state = readPendingNavigationState(identity)
                // A newer intent for this same item replaced the rejected one
                // while it was in flight. That newer intent owns the item's
                // optimistic membership; only the response is stale here.
                if (state.shortcutOperations.none { it == operation }) return true
                val remaining = state.shortcutOperations.filterNot { it == operation }
                writePendingNavigationState(
                    identity,
                    state.copy(shortcutOperations = remaining),
                )
                // The outbox holds at most one operation per item identity, so
                // undoing this one's membership change cannot disturb a
                // sibling's optimistic state, and no remaining operation
                // re-applies it. The exact pre-edit membership is not
                // recorded: re-pinning an already pinned item is the only case
                // this can revert too far, and dropping the operation clears
                // the pending marker, so the effective fetch that follows
                // repaints the authoritative document either way.
                val reverted = applyShortcutOperation(
                    _shortcuts.value,
                    operation.copy(present = !operation.present),
                )
                _shortcuts.value = reverted
                cacheValue(
                    identity,
                    SettingKeys.NAV_SHORTCUTS,
                    UiCustomizationCodec.encodeShortcuts(reverted),
                )
            }
        }
        return true
    }

    /**
     * Re-mints the head operation's idempotency key in place, so the retry is
     * the same declarative intent under an id the server has never seen.
     */
    private fun rotateShortcutMutationId(
        identity: CacheIdentity,
        operation: PendingShortcutOperation,
    ): Boolean {
        synchronized(authoringStateLock) {
            if (activeIdentity != identity) return false
            synchronized(navigationOutboxLock) {
                val state = readPendingNavigationState(identity)
                val index = state.shortcutOperations.indexOf(operation)
                // Superseded while in flight: the newer operation at the head
                // carries its own id and needs no rotation.
                if (index < 0) return true
                val rotated = state.shortcutOperations.toMutableList()
                rotated[index] = operation.copy(mutationId = newSettingMutationId())
                writePendingNavigationState(
                    identity,
                    state.copy(shortcutOperations = rotated),
                )
            }
        }
        return true
    }

    private fun shortcutFailed(
        operation: PendingShortcutOperation,
        detail: String,
        disposition: String,
    ) {
        SiloLog.w(
            CATEGORY,
            TAG,
            "shortcut ${if (operation.present) "pin" else "unpin"} " +
                "${UiCustomizationCodec.identity(operation.item)} failed ($detail); $disposition",
        )
    }

    /** Returns device overrides that could not yet be cleared. */
    private suspend fun flushPendingDeviceDeletes(binding: RequestBinding): Set<String> =
        mutationMutex.withLock {
            val identity = binding.identity
            val stillPending = mutableSetOf<String>()
            for (key in DEVICE_OVERRIDE_KEYS) {
                if (!isPendingDeviceDelete(identity, key)) continue
                if (activeIdentity != identity || !currentOwnerMatches(binding)) {
                    stillPending += key
                    continue
                }
                val result = repository.clearProfileDeviceValue(
                    key,
                    profileId = identity.profileId,
                    authScope = binding.authScope,
                    clientFamily = identity.family,
                )
                if (result is ApiResult.Success && activeIdentity == identity &&
                    currentOwnerMatches(binding)
                ) {
                    markPendingDeviceDelete(identity, key, false)
                } else {
                    stillPending += key
                }
            }
            stillPending
        }

    private suspend fun currentBinding(): RequestBinding? {
        val currentFamily = familyProvider()
        tokenManager.snapshotCurrentScope()?.let { snapshot ->
            val profileId = snapshot.profileId?.takeIf { it.isNotBlank() }
                ?: return null
            val serverUrl = snapshot.serverUrl.trimEnd('/').takeIf { it.isNotBlank() }
                ?: return null
            return RequestBinding(
                identity = CacheIdentity(
                    serverUrl = serverUrl,
                    profileId = profileId,
                    serverId = snapshot.serverId,
                    credentialOwnerKey = credentialOwnerKey(snapshot),
                    family = currentFamily,
                ),
                authScope = snapshot,
            )
        }
        // Common/test token managers may not implement scoped snapshots. The
        // production Android manager always does, so live multi-server state
        // takes the atomic path above rather than pairing two separate reads.
        val profileId = tokenManager.getProfileId()?.takeIf { it.isNotBlank() } ?: return null
        val serverUrl = tokenManager.getServerUrl().trimEnd('/').takeIf { it.isNotBlank() }
            ?: return null
        return RequestBinding(
            identity = CacheIdentity(
                serverUrl = serverUrl,
                profileId = profileId,
                serverId = null,
                credentialOwnerKey = LEGACY_CREDENTIAL_OWNER,
                family = currentFamily,
            ),
            authScope = null,
        )
    }

    /**
     * Snapshot fields such as identity generation may rotate while a pinned
     * request is in flight. A successful receipt still belongs to the same
     * durable operation when its stable credential owner is active.
     */
    private suspend fun currentOwnerMatches(binding: RequestBinding): Boolean =
        currentBinding()?.identity == binding.identity

    // credentialOwnerId is stable across PIN-token rotation, server switches,
    // and process death, but is replaced on sign-out/re-login. Older/custom
    // token managers fall back to the captured epoch when available; only
    // hand-built epoch-zero scopes retain the legacy server/profile owner.
    private fun credentialOwnerKey(snapshot: AuthScopeSnapshot): String =
        snapshot.credentialGenerationId
            ?.takeIf { it.isNotBlank() }
            ?.let { "overlay:$it" }
            ?: snapshot.credentialOwnerId
                ?.takeIf { it.isNotBlank() }
                ?.let { "persistent:${sha256(it)}" }
            ?: snapshot.credentialEpoch
                .takeIf { it != 0L }
                ?.let { "persistent_epoch:$it" }
            ?: LEGACY_CREDENTIAL_OWNER

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * Resolve and activate the current atomic auth identity inside the
     * serialized authoring lane before any local mutation is persisted or
     * painted. This closes the window where the registry has switched to B
     * while [activeIdentity] still points at A awaiting lifecycle refresh.
     */
    private fun authorMutation(action: (RequestBinding) -> Unit) {
        reclassifyClientFamily()
        val requestedEpoch = synchronized(authoringStateLock) { authoringEpoch }
        authoringCommands.trySend(AuthoringCommand(requestedEpoch, action))
    }

    private suspend fun executeAuthoringCommand(command: AuthoringCommand) {
        authoringMutex.withLock {
            val binding = currentBinding() ?: return@withLock
            synchronized(authoringStateLock) {
                if (command.epoch != authoringEpoch) return@synchronized
                activateIdentity(binding.identity)
                command.action(binding)
            }
        }
    }

    private fun activateIdentity(identity: CacheIdentity) {
        if (activeIdentity == identity) return
        activeIdentity = identity
        _uiCustomizationSupported.value = null
        loadCached(identity)
    }

    private fun loadCached(identity: CacheIdentity) {
        // Never let one profile's last in-memory selection bleed into another
        // profile when the new identity has no cached value (for example while
        // offline on first use).
        _primaryMenu.value = null
        _shortcuts.value = NavigationShortcuts.EMPTY
        _cardPresentation.value = CardPresentation.DEFAULT
        _primaryMenuSource.value = null
        _cardPresentationSource.value = null

        cachedUiJson(identity, SettingKeys.NAV_PRIMARY_MENU)?.let { value ->
            _primaryMenu.value = if (value is JsonNull) null else
                UiCustomizationCodec.parsePrimaryMenu(value) ?: _primaryMenu.value
        }
        val cachedShortcuts = cachedJson(identity, SettingKeys.NAV_SHORTCUTS)
            ?.let(UiCustomizationCodec::parseShortcuts)
            ?: NavigationShortcuts.EMPTY
        _shortcuts.value = applyShortcutOperations(
            cachedShortcuts,
            pendingShortcutOperations(identity),
        )
        cachedUiJson(identity, SettingKeys.UI_CARD_PRESENTATION)?.let { value ->
            UiCustomizationCodec.parseCardPresentation(value)?.let { _cardPresentation.value = it }
        }
        _primaryMenuSource.value = cachedSource(identity, SettingKeys.NAV_PRIMARY_MENU)
        _cardPresentationSource.value = cachedSource(identity, SettingKeys.UI_CARD_PRESENTATION)
        if (cachedPendingMutation(identity, SettingKeys.NAV_PRIMARY_MENU) != null &&
            _primaryMenuSource.value != SettingScope.PROFILE_DEVICE.wire
        ) {
            _primaryMenuSource.value = SettingScope.PROFILE_CLIENT.wire
        }
    }

    private fun cachedJson(identity: CacheIdentity, key: String): JsonElement? {
        val raw = cache.getString(identity.serverUrl, storageKey(identity, key))
        if (raw.isBlank()) return null
        return runCatching { JSON.parseToJsonElement(raw) }.getOrNull()
    }

    private fun cacheValue(identity: CacheIdentity, key: String, value: JsonElement) {
        cache.putString(identity.serverUrl, storageKey(identity, key), value.toString())
    }

    private fun cachedUiJson(identity: CacheIdentity, key: String): JsonElement? =
        cachedPendingMutation(identity, key)?.value ?: cachedJson(identity, key)

    private fun isPending(identity: CacheIdentity, key: String): Boolean =
        cachedPendingMutation(identity, key) != null ||
            cache.getBoolean(identity.serverUrl, pendingKey(identity, key), false)

    private fun markPending(identity: CacheIdentity, key: String, pending: Boolean) {
        markPendingFlag(identity, key, pending)
        if (!pending) {
            cachePendingMutation(identity, key, null)
            cacheLegacyMutationId(identity, key, null)
        }
    }

    private fun markPendingFlag(identity: CacheIdentity, key: String, pending: Boolean) {
        cache.putBoolean(identity.serverUrl, pendingKey(identity, key), pending)
    }

    private fun cachedPendingMutation(identity: CacheIdentity, key: String): PendingMutation? {
        if (key == SettingKeys.NAV_PRIMARY_MENU) {
            return synchronized(navigationOutboxLock) {
                readPendingNavigationState(identity).menuMutations[identity.family.wire]
            }
        }
        val raw = cache.getString(identity.serverUrl, pendingMutationKey(identity, key))
        if (raw.isBlank()) return null
        val element = runCatching { JSON.parseToJsonElement(raw) }.getOrNull() ?: return null
        return parsePendingMutation(element)
    }

    private fun cachePendingMutation(
        identity: CacheIdentity,
        key: String,
        pending: PendingMutation?,
    ) {
        if (key == SettingKeys.NAV_PRIMARY_MENU) {
            synchronized(navigationOutboxLock) {
                val state = readPendingNavigationState(identity)
                val nextMenus = if (pending == null) {
                    state.menuMutations - identity.family.wire
                } else {
                    state.menuMutations + (identity.family.wire to pending)
                }
                writePendingNavigationState(
                    identity,
                    state.copy(menuMutations = nextMenus),
                )
            }
            return
        }
        val raw = pending?.let(::encodePendingMutation)?.toString().orEmpty()
        cache.putString(identity.serverUrl, pendingMutationKey(identity, key), raw)
    }

    private fun parsePendingMutation(element: JsonElement): PendingMutation? {
        val objectValue = element as? JsonObject ?: return null
        if (objectValue.keys != setOf("value", "mutation_id")) return null
        val value = objectValue["value"] ?: return null
        val mutationIdValue = objectValue["mutation_id"]
        val mutationId = when (mutationIdValue) {
            is JsonNull -> null
            is JsonPrimitive -> mutationIdValue.takeIf { it.isString }?.contentOrNull
            else -> null
        }
        if (mutationIdValue !is JsonNull && mutationId.isNullOrBlank()) return null
        return PendingMutation(value, mutationId)
    }

    private fun encodePendingMutation(mutation: PendingMutation): JsonObject = buildJsonObject {
        put("value", mutation.value)
        put("mutation_id", mutation.mutationId?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun cachedLegacyMutationId(identity: CacheIdentity, key: String): String? =
        cache.getString(identity.serverUrl, mutationIdKey(identity, key))
            .takeIf { it.isNotBlank() }

    private fun cacheLegacyMutationId(identity: CacheIdentity, key: String, mutationId: String?) {
        cache.putString(identity.serverUrl, mutationIdKey(identity, key), mutationId.orEmpty())
    }

    private fun cachedSource(identity: CacheIdentity, key: String): String? =
        cache.getString(identity.serverUrl, sourceKey(identity, key)).takeIf { it.isNotBlank() }

    private fun cacheSource(identity: CacheIdentity, key: String, source: String?) {
        cache.putString(identity.serverUrl, sourceKey(identity, key), source.orEmpty())
    }

    private fun setFamilySourceUnlessDeviceOverride(
        identity: CacheIdentity,
        key: String,
        source: MutableStateFlow<String?>,
    ) {
        if (source.value == SettingScope.PROFILE_DEVICE.wire) return
        source.value = SettingScope.PROFILE_CLIENT.wire
        cacheSource(identity, key, source.value)
    }

    private fun effectiveSource(source: String, scope: String?): String? =
        (scope ?: source).takeUnless { it == "default" }

    private fun storageKey(identity: CacheIdentity, key: String): String =
        "$CACHE_PREFIX.${identity.serverId ?: LEGACY_SERVER_ID}.${identity.profileId}." +
            "${identity.credentialOwnerKey}." +
            "${if (key == SettingKeys.NAV_SHORTCUTS) PROFILE_WIDE else identity.family.wire}.$key"

    private fun pendingKey(identity: CacheIdentity, key: String): String =
        "${storageKey(identity, key)}.pending"

    private fun mutationIdKey(identity: CacheIdentity, key: String): String =
        "${storageKey(identity, key)}.mutation_id"

    private fun pendingMutationKey(identity: CacheIdentity, key: String): String =
        "${storageKey(identity, key)}.pending_mutation"

    private fun sourceKey(identity: CacheIdentity, key: String): String =
        "${storageKey(identity, key)}.source"

    private fun pendingDeviceDeleteKey(identity: CacheIdentity, key: String): String =
        "${storageKey(identity, key)}.pending_device_delete"

    private fun isPendingDeviceDelete(identity: CacheIdentity, key: String): Boolean =
        cache.getBoolean(identity.serverUrl, pendingDeviceDeleteKey(identity, key), false)

    private fun markPendingDeviceDelete(identity: CacheIdentity, key: String, pending: Boolean) {
        cache.putBoolean(identity.serverUrl, pendingDeviceDeleteKey(identity, key), pending)
    }

    private fun pendingShortcutOperations(identity: CacheIdentity): List<PendingShortcutOperation> =
        synchronized(navigationOutboxLock) {
            readPendingNavigationState(identity).shortcutOperations
        }

    private fun hasPendingShortcutOps(identity: CacheIdentity): Boolean =
        synchronized(navigationOutboxLock) {
            readPendingNavigationState(identity).shortcutOperations.isNotEmpty()
        }

    private fun acceptShortcutOperationSuccess(
        identity: CacheIdentity,
        operation: PendingShortcutOperation,
        serverValue: JsonElement,
    ): Boolean {
        val remote = UiCustomizationCodec.parseShortcuts(serverValue) ?: return false
        synchronized(authoringStateLock) {
            if (activeIdentity != identity) return false
            synchronized(navigationOutboxLock) {
                val state = readPendingNavigationState(identity)
                val remaining = state.shortcutOperations.filterNot { it == operation }
                writePendingNavigationState(
                    identity,
                    state.copy(shortcutOperations = remaining),
                )
                val optimistic = applyShortcutOperations(remote, remaining)
                _shortcuts.value = optimistic
                cacheValue(
                    identity,
                    SettingKeys.NAV_SHORTCUTS,
                    UiCustomizationCodec.encodeShortcuts(optimistic),
                )
            }
        }
        return true
    }

    private fun readPendingNavigationState(
        identity: CacheIdentity,
    ): PendingNavigationState {
        val raw = cache.getString(identity.serverUrl, navigationOutboxKey(identity))
        if (raw.isBlank()) return PendingNavigationState()
        val root = runCatching { JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return PendingNavigationState()
        val menus: Map<String, PendingMutation> = when (root.keys) {
            setOf("menus", "shortcut_operations") -> {
                val menuObject = root["menus"] as? JsonObject
                    ?: return PendingNavigationState()
                buildMap {
                    menuObject.forEach { (familyWire, element) ->
                        val mutation = parsePendingMutation(element)
                            ?: return PendingNavigationState()
                        put(familyWire, mutation)
                    }
                }
            }
            // A pre-map development build did not record which family owned
            // its menu. Never guess after a phone/tablet reclassification;
            // discard that ambiguous menu while retaining the independently
            // profile-wide shortcut operations below.
            setOf("menu", "shortcut_operations") -> emptyMap()
            else -> return PendingNavigationState()
        }
        val operations = root["shortcut_operations"] as? JsonArray
            ?: return PendingNavigationState()
        val parsedOperations = operations.map { element ->
            val value = element as? JsonObject ?: return PendingNavigationState()
            if (value.keys != setOf("identity", "item", "present", "mutation_id")) {
                return PendingNavigationState()
            }
            val item = UiCustomizationCodec.parseShortcutItem(value["item"])
                ?: return PendingNavigationState()
            val semanticIdentity = (value["identity"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?: return PendingNavigationState()
            if (semanticIdentity != UiCustomizationCodec.identity(item)) {
                return PendingNavigationState()
            }
            val present = (value["present"] as? JsonPrimitive)?.booleanOrNull
                ?: return PendingNavigationState()
            val mutationId = (value["mutation_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return PendingNavigationState()
            PendingShortcutOperation(item, present, mutationId)
        }
        return PendingNavigationState(menus, parsedOperations)
    }

    private fun writePendingNavigationState(
        identity: CacheIdentity,
        state: PendingNavigationState,
    ) {
        val raw = if (state.menuMutations.isEmpty() && state.shortcutOperations.isEmpty()) {
            ""
        } else {
            buildJsonObject {
                put("menus", buildJsonObject {
                    state.menuMutations.forEach { (familyWire, mutation) ->
                        put(familyWire, encodePendingMutation(mutation))
                    }
                })
                put("shortcut_operations", buildJsonArray {
                    state.shortcutOperations.forEach { operation ->
                        add(buildJsonObject {
                            put("identity", UiCustomizationCodec.identity(operation.item))
                            put("item", checkNotNull(
                                UiCustomizationCodec.encodeShortcutItem(operation.item),
                            ))
                            put("present", operation.present)
                            put("mutation_id", operation.mutationId)
                        })
                    }
                })
            }.toString()
        }
        cache.putString(identity.serverUrl, navigationOutboxKey(identity), raw)
    }

    private fun applyShortcutOperations(
        base: NavigationShortcuts,
        operations: List<PendingShortcutOperation>,
    ): NavigationShortcuts = operations.fold(base, ::applyShortcutOperation)

    private fun applyShortcutOperation(
        base: NavigationShortcuts,
        operation: PendingShortcutOperation,
    ): NavigationShortcuts {
        val identity = UiCustomizationCodec.identity(operation.item)
        val existing = base.items.firstOrNull { UiCustomizationCodec.identity(it) == identity }
        if (operation.present && existing != null) return base
        val withoutItem = base.items.filterNot { UiCustomizationCodec.identity(it) == identity }
        return if (operation.present) {
            NavigationShortcuts(withoutItem + operation.item)
        } else {
            NavigationShortcuts(withoutItem)
        }
    }

    private fun navigationOutboxKey(identity: CacheIdentity): String =
        "$CACHE_PREFIX.${identity.serverId ?: LEGACY_SERVER_ID}.${identity.profileId}." +
            "${identity.credentialOwnerKey}.navigation_outbox"

    private fun nextGeneration(key: String): Long = synchronized(generationLock) {
        ((generations[key] ?: 0L) + 1L).also { generations[key] = it }
    }

    private fun isCurrent(key: String, generation: Long): Boolean = synchronized(generationLock) {
        (generations[key] ?: 0L) == generation
    }

    private fun snapshotGenerations(keys: List<String>): Map<String, Long> =
        synchronized(generationLock) {
            keys.associateWith { key -> generations[key] ?: 0L }
        }

    private data class CacheIdentity(
        val serverUrl: String,
        val profileId: String,
        val serverId: String?,
        val credentialOwnerKey: String,
        val family: SiloClientFamily,
    )

    private data class RequestBinding(
        val identity: CacheIdentity,
        val authScope: AuthScopeSnapshot?,
    )

    private data class AuthoringCommand(
        val epoch: Long,
        val action: (RequestBinding) -> Unit,
    )

    private data class PendingMutation(
        val value: JsonElement,
        val mutationId: String?,
    )

    private data class PendingShortcutOperation(
        val item: PrimaryMenuItem,
        val present: Boolean,
        val mutationId: String,
    )

    private data class PendingNavigationState(
        val menuMutations: Map<String, PendingMutation> = emptyMap(),
        val shortcutOperations: List<PendingShortcutOperation> = emptyList(),
    )

    private companion object {
        const val TAG = "UiCustomizationStore"
        val CATEGORY = DiagnosticsLogCategory.NETWORK
        val JSON = Json { ignoreUnknownKeys = false }
        const val CACHE_PREFIX = "ui_customization.v1"
        const val LEGACY_SERVER_ID = "single_server"
        const val LEGACY_CREDENTIAL_OWNER = "legacy_owner"
        const val PROFILE_WIDE = "profile"
        val ALL_KEYS = listOf(
            SettingKeys.NAV_PRIMARY_MENU,
            SettingKeys.NAV_SHORTCUTS,
            SettingKeys.UI_CARD_PRESENTATION,
        )
        val PENDING_VALUE_KEYS = listOf(
            SettingKeys.NAV_PRIMARY_MENU,
            SettingKeys.UI_CARD_PRESENTATION,
        )
        val DEVICE_OVERRIDE_KEYS = listOf(
            SettingKeys.NAV_PRIMARY_MENU,
            SettingKeys.UI_CARD_PRESENTATION,
        )

        /** `mutation_id_conflict`: this id, not this intent, was refused. */
        const val HTTP_CONFLICT = 409

        /**
         * Retrying can help: the server fell over, throttled us, timed out, or
         * the session token was mid refresh. Every other 4xx is the contract
         * refusing this membership change — an item the server will not store,
         * a scope it will not accept — where the identical retry fails forever.
         * Mirrors [ServerSettingsFlusher]'s classification, minus 409, which
         * this outbox recovers from by re-minting the id rather than dropping
         * the operation.
         */
        fun isRetryableHttp(code: Int): Boolean =
            code >= 500 || code == 408 || code == 429 || code == 401
    }
}
