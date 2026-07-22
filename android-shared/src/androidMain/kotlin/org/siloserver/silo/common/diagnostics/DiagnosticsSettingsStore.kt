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
)

@Serializable
data class SentDiagnosticsReport(
    val shortId: String,
    val sentAtEpochMs: Long,
)

fun interface DiagnosticsBindingPurger {
    suspend fun purge(binding: DiagnosticsBinding)
}

class DiagnosticsSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val bindingPurger: DiagnosticsBindingPurger,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
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
        val keys = keys(binding)
        dataStore.edit { preferences ->
            preferences[keys.consentMode] = mode.name
            preferences[keys.noticeVersion] = noticeVersion
            if (mode == DiagnosticsConsentMode.NEVER) {
                preferences[DEBUG_LOGGING_KEY] = false
                preferences.remove(keys.sentHistory)
            }
        }
        if (mode == DiagnosticsConsentMode.NEVER) bindingPurger.purge(binding)
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
        )
        dataStore.edit { preferences -> preferences[CACHED_CONTEXT_KEY] = JSON.encodeToString(cached) }
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

    suspend fun recordSent(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long) {
        require(shortId.isNotBlank()) { "shortId must not be blank" }
        val keys = keys(binding)
        dataStore.edit { preferences ->
            val existing = decodeHistory(preferences[keys.sentHistory])
            val updated = (listOf(SentDiagnosticsReport(shortId, sentAtEpochMs)) + existing)
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

    suspend fun purgeBinding(binding: DiagnosticsBinding) {
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
        bindingPurger.purge(binding)
    }

    private fun decodeHistory(raw: String?): List<SentDiagnosticsReport> =
        raw?.let { encoded -> runCatching { JSON.decodeFromString<List<SentDiagnosticsReport>>(encoded) }.getOrNull() }
            .orEmpty()

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
        val CACHED_CONTEXT_KEY = stringPreferencesKey("diagnostics.last_context")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
