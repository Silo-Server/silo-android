package org.siloserver.silo.common.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities

@Serializable
data class DiagnosticsBinding(
    val serverInstanceId: String,
    val accountUserId: String,
) {
    init {
        require(serverInstanceId.isNotBlank()) { "serverInstanceId must not be blank" }
        require(accountUserId.isNotBlank()) { "accountUserId must not be blank" }
    }
}

enum class DiagnosticsConsentMode { ASK, ALWAYS, NEVER }

data class DiagnosticsConsentRecord(
    val mode: DiagnosticsConsentMode,
    val noticeVersion: Int,
)

@Serializable
data class CachedDiagnosticsContext(
    val binding: DiagnosticsBinding,
    val localServerId: String? = null,
    val credentialFingerprint: String? = null,
    val profileId: String?,
    val profileEligible: Boolean,
    val noticeVersion: Int,
    val status: DiagnosticsAvailabilityStatus,
    val acceptedSchemaVersions: Set<Int>,
    val maxBundleBytes: Long,
    val maxManifestBytes: Long,
    val retentionDays: Int,
    val sourceProfileId: String? = profileId,
    val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
)

@Serializable
data class SentDiagnosticsReport(
    val shortId: String,
    val sentAtEpochMs: Long,
    val state: String = "accepted",
)

@Serializable
private data class DiagnosticsBindingIndex(
    val byLocalServerId: Map<String, List<DiagnosticsBinding>> = emptyMap(),
)

@Serializable
private data class DiagnosticsErasureIndex(
    val bindings: List<DiagnosticsBinding> = emptyList(),
)

fun interface DiagnosticsBindingPurger {
    suspend fun purge(binding: DiagnosticsBinding, includeLiveCapture: Boolean)
}

fun interface DiagnosticsAllEvidencePurger {
    suspend fun purge(includeLiveCapture: Boolean)
}

class DiagnosticsSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val bindingPurger: DiagnosticsBindingPurger,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    private val afterErasureIntentPersisted: suspend (DiagnosticsBinding) -> Unit = {},
    private val allEvidencePurger: DiagnosticsAllEvidencePurger? = null,
) {
    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
    }

    suspend fun consent(
        binding: DiagnosticsBinding,
        currentNoticeVersion: Int,
    ): DiagnosticsConsentRecord {
        require(currentNoticeVersion > 0) { "currentNoticeVersion must be positive" }
        val keys = keys(binding)
        var result: DiagnosticsConsentRecord? = null
        dataStore.edit { preferences ->
            val storedMode = preferences[keys.consentMode]
                ?.let { raw -> DiagnosticsConsentMode.entries.firstOrNull { it.name == raw } }
                ?: DiagnosticsConsentMode.ASK
            val storedNotice = preferences[keys.noticeVersion] ?: currentNoticeVersion
            result = if (storedMode == DiagnosticsConsentMode.ALWAYS && storedNotice != currentNoticeVersion) {
                preferences[keys.consentMode] = DiagnosticsConsentMode.ASK.name
                preferences[keys.noticeVersion] = currentNoticeVersion
                DiagnosticsConsentRecord(DiagnosticsConsentMode.ASK, currentNoticeVersion)
            } else {
                DiagnosticsConsentRecord(storedMode, storedNotice)
            }
        }
        return checkNotNull(result)
    }

    suspend fun setConsent(
        binding: DiagnosticsBinding,
        mode: DiagnosticsConsentMode,
        noticeVersion: Int,
    ) {
        require(noticeVersion > 0) { "noticeVersion must be positive" }
        repairCorruptErasureIndex()
        if (mode != DiagnosticsConsentMode.NEVER) {
            retryPendingErasure(binding, includeLiveCapture = true)
        }
        val keys = keys(binding)
        dataStore.edit { preferences ->
            preferences[keys.consentMode] = mode.name
            preferences[keys.noticeVersion] = noticeVersion
            if (mode == DiagnosticsConsentMode.NEVER) {
                preferences[DEBUG_LOGGING_KEY] = false
                preferences.remove(keys.sentHistory)
                val pending = decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) ?: DiagnosticsErasureIndex()
                preferences.storeErasureIndex(
                    pending.copy(bindings = (pending.bindings + binding).distinct()),
                )
            }
        }
        if (mode == DiagnosticsConsentMode.NEVER) {
            // Test seam models process death in the only meaningful crash
            // window: NEVER and its erasure authority are durable, but no
            // evidence has been removed yet.
            afterErasureIntentPersisted(binding)
            retryPendingErasure(binding, includeLiveCapture = true)
        }
    }

    suspend fun retryPendingErasures(currentBinding: DiagnosticsBinding? = null) {
        val pending = pendingErasureBindings()
        pending.sortedBy { it != currentBinding }.forEach { binding ->
            retryPendingErasure(binding, includeLiveCapture = binding == currentBinding)
        }
    }

    suspend fun pendingErasureBindings(): List<DiagnosticsBinding> =
        decodeErasureIndex(dataStore.data.first()[ERASURE_INDEX_KEY])?.bindings?.distinct()
            ?: repairCorruptErasureIndex().let { emptyList() }

    private suspend fun retryPendingErasure(
        binding: DiagnosticsBinding,
        includeLiveCapture: Boolean,
    ) {
        if (binding !in pendingErasureBindings()) return
        bindingPurger.purge(binding, includeLiveCapture)
        dataStore.edit { preferences ->
            val pending = decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) ?: DiagnosticsErasureIndex()
            preferences.storeErasureIndex(
                pending.copy(bindings = pending.bindings.filterNot { it == binding }),
            )
        }
    }

    suspend fun demoteAlwaysToAsk(binding: DiagnosticsBinding, noticeVersion: Int): Boolean {
        require(noticeVersion > 0) { "noticeVersion must be positive" }
        val keys = keys(binding)
        var demoted = false
        dataStore.edit { preferences ->
            if (
                preferences[keys.consentMode] == DiagnosticsConsentMode.ALWAYS.name &&
                preferences[keys.noticeVersion] == noticeVersion
            ) {
                preferences[keys.consentMode] = DiagnosticsConsentMode.ASK.name
                preferences[keys.noticeVersion] = noticeVersion
                demoted = true
            }
        }
        return demoted
    }

    suspend fun debugLogging(): Boolean =
        dataStore.data.first()[DEBUG_LOGGING_KEY] ?: false

    /** Hosted collection is the device default; self-hosted remains an explicit compatibility choice. */
    suspend fun destinationKind(): DiagnosticsDestinationKind =
        dataStore.data.first()[DESTINATION_KIND_KEY]
            ?.let { raw -> DiagnosticsDestinationKind.entries.firstOrNull { it.name == raw } }
            ?: DiagnosticsDestinationKind.HOSTED

    /**
     * Fail-closed send-time policy check. Callers perform this while holding
     * [DiagnosticsPrivacyBarrier], immediately before starting transport.
     */
    suspend fun permitsUpload(
        binding: PendingReportBinding,
        noticeVersion: Int,
        requireAlwaysConsent: Boolean,
    ): Boolean {
        val preferences = dataStore.data.first()
        val destination = preferences[DESTINATION_KIND_KEY]
            ?.let { raw -> DiagnosticsDestinationKind.entries.firstOrNull { it.name == raw } }
            ?: DiagnosticsDestinationKind.HOSTED
        if (destination != binding.destinationKind) return false
        val erasures = decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) ?: return false
        if (binding.binding in erasures.bindings) return false
        if (!requireAlwaysConsent) return true

        val keys = keys(binding.binding)
        val mode = preferences[keys.consentMode]
            ?.let { raw -> DiagnosticsConsentMode.entries.firstOrNull { it.name == raw } }
            ?: DiagnosticsConsentMode.ASK
        return mode == DiagnosticsConsentMode.ALWAYS && preferences[keys.noticeVersion] == noticeVersion
    }

    suspend fun setDestinationKind(destinationKind: DiagnosticsDestinationKind) {
        dataStore.edit { preferences -> preferences[DESTINATION_KIND_KEY] = destinationKind.name }
    }

    suspend fun hostedCapabilities(): HostedDiagnosticsCapabilities? =
        dataStore.data.first()[HOSTED_CAPABILITIES_KEY]?.let { encoded ->
            runCatching { JSON.decodeFromString<HostedDiagnosticsCapabilities>(encoded) }.getOrNull()
        }

    suspend fun cacheHostedCapabilities(capabilities: HostedDiagnosticsCapabilities) {
        dataStore.edit { preferences ->
            preferences[HOSTED_CAPABILITIES_KEY] = JSON.encodeToString(capabilities)
        }
    }

    suspend fun hostedBindingOwner(localServerId: String): String? {
        require(localServerId.isNotBlank())
        return dataStore.data.first()[hostedBindingOwnerKey(localServerId)]?.takeIf(String::isNotBlank)
    }

    suspend fun cacheHostedBindingOwner(localServerId: String, owner: String) {
        require(localServerId.isNotBlank())
        require(owner.isNotBlank())
        dataStore.edit { preferences -> preferences[hostedBindingOwnerKey(localServerId)] = owner }
    }

    suspend fun setDebugLogging(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[DEBUG_LOGGING_KEY] = enabled }
    }

    suspend fun cacheContext(context: DiagnosticsCaptureContext) {
        val cached = CachedDiagnosticsContext(
            binding = context.binding,
            localServerId = context.localServerId,
            credentialFingerprint = context.credentialFingerprint,
            profileId = context.profileId,
            profileEligible = context.profileEligible,
            noticeVersion = context.noticeVersion,
            status = context.status,
            acceptedSchemaVersions = context.acceptedSchemaVersions,
            maxBundleBytes = context.maxBundleBytes,
            maxManifestBytes = context.maxManifestBytes,
            retentionDays = context.retentionDays,
            sourceProfileId = context.sourceProfileId,
            destinationKind = context.destinationKind,
        )
        dataStore.edit { preferences ->
            preferences[CACHED_CONTEXT_KEY] = JSON.encodeToString(cached)
            context.localServerId?.takeIf(String::isNotBlank)?.let { localServerId ->
                val index = decodeBindingIndex(preferences[BINDING_INDEX_KEY])
                val bindings = (index.byLocalServerId[localServerId].orEmpty() + context.binding).distinct()
                preferences.storeBindingIndex(
                    index.copy(byLocalServerId = index.byLocalServerId + (localServerId to bindings)),
                )
            }
        }
    }

    suspend fun cachedContext(): CachedDiagnosticsContext? =
        dataStore.data.first()[CACHED_CONTEXT_KEY]?.let { raw ->
            runCatching { JSON.decodeFromString<CachedDiagnosticsContext>(raw) }.getOrNull()
        }

    suspend fun clearCachedContext(binding: DiagnosticsBinding? = null) {
        dataStore.edit { preferences ->
            val cached = preferences[CACHED_CONTEXT_KEY]?.let { raw ->
                runCatching { JSON.decodeFromString<CachedDiagnosticsContext>(raw) }.getOrNull()
            }
            if (binding == null || cached?.binding == binding) preferences.remove(CACHED_CONTEXT_KEY)
        }
    }

    suspend fun recordSent(
        binding: DiagnosticsBinding,
        shortId: String,
        sentAtEpochMs: Long,
        state: String = "accepted",
    ) {
        require(shortId.isNotBlank()) { "shortId must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        val keys = keys(binding)
        dataStore.edit { preferences ->
            val consentMode = preferences[keys.consentMode]
                ?.let { raw -> DiagnosticsConsentMode.entries.firstOrNull { it.name == raw } }
            val erasures = decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) ?: return@edit
            val erasurePending = binding in erasures.bindings
            if (consentMode == DiagnosticsConsentMode.NEVER || erasurePending) {
                // A direct WorkManager upload can settle after Turn Off has
                // durably won. Never recreate history for an identity whose
                // local/remote erasure is pending or whose consent is NEVER.
                return@edit
            }
            val existing = decodeHistory(preferences[keys.sentHistory])
            val updated = (listOf(SentDiagnosticsReport(shortId, sentAtEpochMs, state)) + existing)
                .distinctBy(SentDiagnosticsReport::shortId)
                .sortedByDescending(SentDiagnosticsReport::sentAtEpochMs)
                .take(historyLimit)
            preferences[keys.sentHistory] = JSON.encodeToString(updated)
        }
    }

    suspend fun sentHistory(binding: DiagnosticsBinding): List<SentDiagnosticsReport> =
        decodeHistory(dataStore.data.first()[keys(binding).sentHistory])
            .sortedByDescending(SentDiagnosticsReport::sentAtEpochMs)
            .take(historyLimit)

    suspend fun purgeBinding(
        binding: DiagnosticsBinding,
        includeLiveCapture: Boolean = true,
    ) {
        scrubBindingMetadata(binding)
        bindingPurger.purge(binding, includeLiveCapture)
        dataStore.edit { preferences ->
            val index = decodeBindingIndex(preferences[BINDING_INDEX_KEY])
            preferences.storeBindingIndex(
                index.copy(
                    byLocalServerId = index.byLocalServerId.mapValues { (_, bindings) ->
                        bindings.filterNot { it == binding }
                    }.filterValues { bindings -> bindings.isNotEmpty() },
                ),
            )
            val pending = decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) ?: DiagnosticsErasureIndex()
            preferences.storeErasureIndex(
                pending.copy(bindings = pending.bindings.filterNot { it == binding }),
            )
        }
    }

    /**
     * Removes user-visible metadata without invoking the evidence purger.
     * The identity gate calls [purgeBinding] synchronously; the coordinator
     * actor repeats this metadata-only half after any queued upload settles so
     * late network bookkeeping cannot revive history from the old identity.
     */
    suspend fun scrubBindingMetadata(binding: DiagnosticsBinding) {
        val prefix = bindingKey(binding)
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { key -> preferences.removeUntyped(key) }
            val cached = preferences[CACHED_CONTEXT_KEY]?.let { raw ->
                runCatching { JSON.decodeFromString<CachedDiagnosticsContext>(raw) }.getOrNull()
            }
            if (cached?.binding == binding) preferences.remove(CACHED_CONTEXT_KEY)
        }
    }

    suspend fun bindingsForLocalServer(localServerId: String): List<DiagnosticsBinding> {
        require(localServerId.isNotBlank()) { "localServerId must not be blank" }
        return decodeBindingIndex(dataStore.data.first()[BINDING_INDEX_KEY])
            .byLocalServerId[localServerId]
            .orEmpty()
            .distinct()
    }

    suspend fun purgeLocalServer(
        localServerId: String,
        fallbackBinding: DiagnosticsBinding? = null,
        allowLegacyAllEvidenceFallback: Boolean = true,
    ) {
        require(localServerId.isNotBlank()) { "localServerId must not be blank" }
        dataStore.edit { preferences -> preferences.remove(hostedBindingOwnerKey(localServerId)) }
        val persistedIndex = decodeBindingIndex(dataStore.data.first()[BINDING_INDEX_KEY])
        val indexedBindings = persistedIndex.byLocalServerId[localServerId]
        if (indexedBindings == null && fallbackBinding == null) {
            if (!allowLegacyAllEvidenceFallback) return
            val migrationComplete = dataStore.data.first()[BINDING_INDEX_MIGRATION_COMPLETE_KEY] ?: false
            if (migrationComplete) {
                // Once the legacy evidence inventory has been drained, an
                // absent entry is authoritative: this server never collected
                // diagnostics under the indexed scheme. Do not erase another
                // server's evidence merely because this target is new.
                return
            }
            // Upgrade boundary: older builds retained reports without a
            // localServerId -> binding index. The removed inactive server
            // cannot be reconstructed from hosted/account hashes, so fail
            // closed once by removing all persisted diagnostics evidence.
            checkNotNull(allEvidencePurger) {
                "legacy diagnostics cleanup requires an all-evidence purger"
            }.purge(includeLiveCapture = false)
            dataStore.edit { preferences ->
                preferences.asMap().keys
                    .filter { key -> key.name.startsWith("diagnostics.binding.") }
                    .forEach { key -> preferences.removeUntyped(key) }
                preferences.remove(CACHED_CONTEXT_KEY)
                preferences.remove(BINDING_INDEX_KEY)
                preferences.remove(ERASURE_INDEX_KEY)
                preferences[BINDING_INDEX_MIGRATION_COMPLETE_KEY] = true
            }
            return
        }
        val bindings = (indexedBindings.orEmpty() + listOfNotNull(fallbackBinding)).distinct()
        bindings.forEach { binding ->
            purgeBinding(binding, includeLiveCapture = false)
        }
        dataStore.edit { preferences ->
            val index = decodeBindingIndex(preferences[BINDING_INDEX_KEY])
            preferences.storeBindingIndex(
                index.copy(byLocalServerId = index.byLocalServerId - localServerId),
            )
        }
    }

    private fun decodeHistory(raw: String?): List<SentDiagnosticsReport> =
        raw?.let { encoded -> runCatching { JSON.decodeFromString<List<SentDiagnosticsReport>>(encoded) }.getOrNull() }
            .orEmpty()

    private fun decodeBindingIndex(raw: String?): DiagnosticsBindingIndex {
        val index = raw?.let { encoded ->
            runCatching { JSON.decodeFromString<DiagnosticsBindingIndex>(encoded) }.getOrNull()
        }
            ?: DiagnosticsBindingIndex()
        return index.copy(byLocalServerId = index.byLocalServerId.filterKeys(String::isNotBlank))
    }

    private fun decodeErasureIndex(raw: String?): DiagnosticsErasureIndex? {
        if (raw == null) return DiagnosticsErasureIndex()
        return runCatching { JSON.decodeFromString<DiagnosticsErasureIndex>(raw) }.getOrNull()
    }

    private suspend fun repairCorruptErasureIndex() {
        val raw = dataStore.data.first()[ERASURE_INDEX_KEY] ?: return
        if (decodeErasureIndex(raw) != null) return
        checkNotNull(allEvidencePurger) {
            "corrupt diagnostics erasure state requires an all-evidence purger"
        }.purge(includeLiveCapture = true)
        dataStore.edit { preferences ->
            if (decodeErasureIndex(preferences[ERASURE_INDEX_KEY]) == null) {
                preferences.remove(ERASURE_INDEX_KEY)
            }
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.storeBindingIndex(
        index: DiagnosticsBindingIndex,
    ) {
        if (index.byLocalServerId.isEmpty()) {
            remove(BINDING_INDEX_KEY)
        } else {
            this[BINDING_INDEX_KEY] = JSON.encodeToString(index)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.storeErasureIndex(
        index: DiagnosticsErasureIndex,
    ) {
        if (index.bindings.isEmpty()) {
            remove(ERASURE_INDEX_KEY)
        } else {
            this[ERASURE_INDEX_KEY] = JSON.encodeToString(index)
        }
    }

    private fun keys(binding: DiagnosticsBinding): BindingKeys {
        val prefix = bindingKey(binding)
        return BindingKeys(
            consentMode = stringPreferencesKey("${prefix}consent_mode"),
            noticeVersion = intPreferencesKey("${prefix}notice_version"),
            sentHistory = stringPreferencesKey("${prefix}sent_history"),
        )
    }

    private fun bindingKey(binding: DiagnosticsBinding): String {
        val input = "${binding.serverInstanceId}\u0000${binding.accountUserId}".encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return buildString(17) {
            append("diagnostics.binding.")
            digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
            append('.')
        }
    }

    private fun hostedBindingOwnerKey(localServerId: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(localServerId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return stringPreferencesKey("diagnostics.hosted.binding_owner.$digest")
    }

    private data class BindingKeys(
        val consentMode: Preferences.Key<String>,
        val noticeVersion: Preferences.Key<Int>,
        val sentHistory: Preferences.Key<String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun androidx.datastore.preferences.core.MutablePreferences.removeUntyped(key: Preferences.Key<*>) {
        remove(key as Preferences.Key<Any>)
    }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 20
        val DEBUG_LOGGING_KEY = booleanPreferencesKey("diagnostics.device.debug_logging")
        val DESTINATION_KIND_KEY = stringPreferencesKey("diagnostics.device.destination_kind")
        val HOSTED_CAPABILITIES_KEY = stringPreferencesKey("diagnostics.hosted.capabilities")
        val CACHED_CONTEXT_KEY = stringPreferencesKey("diagnostics.last_context")
        val BINDING_INDEX_KEY = stringPreferencesKey("diagnostics.binding_index")
        val BINDING_INDEX_MIGRATION_COMPLETE_KEY =
            booleanPreferencesKey("diagnostics.binding_index_migration_complete")
        val ERASURE_INDEX_KEY = stringPreferencesKey("diagnostics.erasure_pending")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
