package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ui.title_art` as resolved for this device.
 *
 * [appliesToAllDevices] is the "Apply to all devices" switch: the contract
 * resolves `profile` ahead of `profile_device` for this key, so a profile value
 * is the profile's all-devices choice and its presence is the switch's state.
 */
data class TitleArtPreference(
    val showTitleArt: Boolean = TitleArtController.DEFAULT_SHOW_TITLE_ART,
    val appliesToAllDevices: Boolean = false,
)

/**
 * Stateless wire half of the "Show title art" setting, shared by the phone and
 * TV apps. The client never reimplements precedence: reads take the server's
 * effective value for this device, and writes follow the `ui.title_art`
 * manifest notes.
 *
 * Every call takes the caller's captured `authority` and passes it to each
 * request it makes, so one change (up to a PUT, a DELETE and a re-read) is
 * bound to one profile: after a profile switch the remaining requests fail as
 * `identity_changed` instead of addressing the new profile.
 */
class TitleArtController(
    private val repository: SettingsRepository,
) {

    sealed interface LoadResult {
        data class Supported(val preference: TitleArtPreference) : LoadResult

        /** Definitive: the server predates the key. Never write. */
        data object Unsupported : LoadResult

        /** Transient failure (offline, 5xx). Keep whatever state was known. */
        data class Failed(val message: String?) : LoadResult
    }

    sealed interface WriteResult {
        /**
         * Every request landed. [resolved] is what the server resolves now, or
         * null when that re-read failed; the caller then keeps its optimistic
         * value, because the write did take effect.
         */
        data class Saved(val resolved: TitleArtPreference?) : WriteResult

        data class Failed(val message: String?) : WriteResult
    }

    suspend fun load(authority: AuthScopeSnapshot? = null): LoadResult {
        when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success -> if (!isSupported(caps.data)) return LoadResult.Unsupported
            // No contract routes: an older server, not a transient failure.
            is ApiResult.Error ->
                return if (caps.code == 404) LoadResult.Unsupported else LoadResult.Failed(caps.message)
            is ApiResult.NetworkError -> return LoadResult.Failed(caps.exception.message)
        }
        return when (val result = resolve(authority)) {
            is ApiResult.Success -> LoadResult.Supported(result.data)
            is ApiResult.Error -> LoadResult.Failed(result.message)
            is ApiResult.NetworkError -> LoadResult.Failed(result.exception.message)
        }
    }

    /**
     * The main switch. While the value applies to all devices it is the
     * profile's value; otherwise it is this device's own.
     */
    suspend fun setShowTitleArt(
        show: Boolean,
        appliesToAllDevices: Boolean,
        authority: AuthScopeSnapshot? = null,
    ): WriteResult {
        val scope = if (appliesToAllDevices) SettingScope.PROFILE else SettingScope.PROFILE_DEVICE
        return put(scope, show, authority) ?: saved(authority)
    }

    /**
     * The "Apply to all devices" switch. [showTitleArt] is the value on screen,
     * which becomes the profile's value (on) or this device's value (off).
     *
     * Turning it off pins this device first, so it keeps its look once the
     * profile value stops winning, and then clears the profile value so every
     * other device returns to its own value or the default. A missing profile
     * value already is the state asked for (the repository reports that 404 as
     * success).
     */
    suspend fun setAppliesToAllDevices(
        enabled: Boolean,
        showTitleArt: Boolean,
        authority: AuthScopeSnapshot? = null,
    ): WriteResult {
        if (enabled) return put(SettingScope.PROFILE, showTitleArt, authority) ?: saved(authority)
        put(SettingScope.PROFILE_DEVICE, showTitleArt, authority)?.let { return it }
        return when (val cleared = repository.clearProfileValue(KEY, authority)) {
            is ApiResult.Success -> saved(authority)
            is ApiResult.Error -> WriteResult.Failed(cleared.message)
            is ApiResult.NetworkError -> WriteResult.Failed(cleared.exception.message)
        }
    }

    /** Null when the write landed, else the failure to report. */
    private suspend fun put(
        scope: SettingScope,
        value: Boolean,
        authority: AuthScopeSnapshot?,
    ): WriteResult.Failed? {
        val result = when (scope) {
            SettingScope.PROFILE -> repository.setProfileValue(KEY, JsonPrimitive(value), authority)
            SettingScope.PROFILE_DEVICE -> repository.setProfileDeviceValue(KEY, JsonPrimitive(value), authority)
            else -> error("ui.title_art allows only profile and profile_device")
        }
        return when (result) {
            is ApiResult.Success -> null
            is ApiResult.Error -> WriteResult.Failed(result.message)
            is ApiResult.NetworkError -> WriteResult.Failed(result.exception.message)
        }
    }

    private suspend fun saved(authority: AuthScopeSnapshot?): WriteResult =
        WriteResult.Saved((resolve(authority) as? ApiResult.Success)?.data)

    private suspend fun resolve(authority: AuthScopeSnapshot?): ApiResult<TitleArtPreference> =
        when (val result = repository.getEffectiveValues(listOf(KEY), authority = authority)) {
            is ApiResult.Success -> {
                val entry = result.data[KEY]
                if (entry == null) {
                    ApiResult.Error(0, "missing_key", "The server did not resolve $KEY")
                } else {
                    ApiResult.Success(preferenceOf(entry))
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }

    companion object {
        const val KEY = SettingKeys.UI_TITLE_ART

        /** Settings manifest revision that introduced `ui.title_art`. */
        const val MIN_CONTRACT_REVISION = 16

        const val DEFAULT_SHOW_TITLE_ART = true

        private const val SETTINGS_API_VERSION = 1

        fun isSupported(capabilities: SettingsContractCapabilities): Boolean =
            capabilities.apiVersion == SETTINGS_API_VERSION &&
                capabilities.manifestRevision >= MIN_CONTRACT_REVISION &&
                capabilities.supportsBatchedEffective

        /** A non-boolean value is not a choice; it resolves to the default. */
        fun preferenceOf(entry: EffectiveSettingValue): TitleArtPreference =
            TitleArtPreference(
                showTitleArt = runCatching { entry.value.jsonPrimitive.booleanOrNull }.getOrNull()
                    ?: DEFAULT_SHOW_TITLE_ART,
                appliesToAllDevices = entry.source == SettingScope.PROFILE.wire,
            )
    }
}
