package org.siloserver.silo.common.i18n

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.common.settings.isTransientSettingsWrite
import org.siloserver.silo.common.store.AccountScopedKeys
import org.siloserver.silo.model.settings.AppLanguage
import org.siloserver.silo.model.settings.AppLocaleReconciler
import org.siloserver.silo.model.settings.AppLocaleReconciler.Ledger
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.SettingsRepository
import java.lang.ref.WeakReference

/**
 * What the App language picker shows.
 *
 * @property supported the server knows `ui.app_language` (revision 28).
 * @property choice this device's effective value for the active profile, as
 *   stored (it may be a language this build doesn't ship); null is Match device.
 * @property languages the languages this build ships.
 */
data class AppLanguageState(
    val supported: Boolean = false,
    val choice: String? = null,
    val languages: List<String> = listOf(AppLanguage.DEFAULT),
)

/**
 * Applies the active profile's `ui.app_language` to the per-app language, for
 * both the phone and TV apps.
 *
 * - The in-app picker ([choose]) saves the profile's value, which follows the
 *   person to every device, and clears this device's own value.
 * - On Android 13+ the per-app language in system settings is the same knob on
 *   this device: a change made there is saved as this device's value for the
 *   active profile.
 * - Refreshes on a server/account/profile change, on foreground and on
 *   reconnect (`ServerDrivenConfigRefresher`), then reconciles. [check] runs
 *   from each activity's `onStart` and `onConfigurationChanged`, so a system
 *   change is seen even while the activity stays started (picture-in-picture,
 *   split screen), and before the server answers.
 * - Each choice is queued per profile in preferences (unlike
 *   `ServerSettingsFlusher`'s in-memory queue, it must survive process death)
 *   and sent in order until it lands; a newer choice replaces an unsent one.
 *   While it waits it is that profile's value, so a stale server answer can't
 *   re-apply over it. A save the server refuses for good is dropped with the
 *   ledger, so the profile's value applies again.
 * - Before sign-in, and while the active profile's value is unknown and the
 *   system value hasn't changed, the app changes nothing: the platform keeps
 *   the language last applied.
 */
class AppLanguageStore(
    context: Context,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val getActiveProfileId: suspend () -> String?,
    private val getServerUrl: suspend () -> String?,
    private val getActiveAccountId: suspend () -> String?,
    private val captureAuthority: suspend () -> AuthScopeSnapshot?,
    identityChanges: Flow<Unit>,
) {
    /** The server's answer for one identity, replaced as a whole. */
    private data class Known(
        val identity: String,
        val authority: AuthScopeSnapshot,
        val supported: Boolean,
        val value: String?,
    )

    /**
     * An unsent choice. [profileWide] (the picker) saves the profile's value and
     * clears this device's; otherwise (system settings) this device's value.
     * [tag] "" is Match device.
     */
    private data class Pending(val profileWide: Boolean, val tag: String) {
        fun encode() = (if (profileWide) PROFILE else DEVICE) + tag

        companion object {
            const val PROFILE = "profile:"
            const val DEVICE = "device:"

            fun decode(raw: String?): Pending? = when {
                raw == null -> null
                raw.startsWith(PROFILE) -> Pending(true, raw.removePrefix(PROFILE))
                raw.startsWith(DEVICE) -> Pending(false, raw.removePrefix(DEVICE))
                else -> null
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val shipped: List<String> by lazy { AppLocales.shipped(appContext) }

    /** Guards the ledger, the pending values and every apply. */
    private val lock = Mutex()

    /** One save at a time, so they reach the server in the order chosen. */
    private val flushLock = Mutex()

    private val _state = MutableStateFlow(AppLanguageState())
    val state: StateFlow<AppLanguageState> = _state.asStateFlow()

    @Volatile private var known: Known? = null

    /** Bumped by every save that lands; a read that began before one is stale. */
    @Volatile private var writes = 0L

    /** Before 13 an apply only lands through a recreation of this activity. */
    @Volatile private var foreground: WeakReference<Activity>? = null

    init {
        scope.launch {
            AppLocales.migrateLegacyTag(appContext)
            identityChanges.collectLatest {
                known = null
                _state.value = AppLanguageState(languages = shipped)
                refresh()
            }
        }
    }

    suspend fun refresh() {
        val identity = currentIdentity() ?: return
        val authority = captureAuthority()?.takeIf { it.belongsTo(identity) } ?: return
        if (currentIdentity() != identity) return
        val supported = when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success -> AppLanguage.isSupported(caps.data)
            // No contract routes: an older server.
            is ApiResult.Error -> if (caps.code == 404) false else return
            is ApiResult.NetworkError -> return
        }
        if (supported) flushPending(identity, authority)
        var value: String? = null
        if (supported) {
            // A save that lands during the read makes the answer stale: read again.
            for (attempt in 1..READ_ATTEMPTS) {
                val readStartedAt = writes
                value = when (val result = repository.getEffectiveValues(listOf(AppLanguage.KEY), authority = authority)) {
                    is ApiResult.Success ->
                        runCatching { result.data[AppLanguage.KEY]?.value?.jsonPrimitive?.contentOrNull }.getOrNull()
                    is ApiResult.Error, is ApiResult.NetworkError -> return
                }
                if (writes == readStartedAt) break
                if (attempt == READ_ATTEMPTS) return
            }
        }
        if (currentIdentity() != identity) return
        known = Known(identity, authority, supported, value)
        // An older server can't hold a queued choice.
        if (!supported) lock.withLock { if (readPending(identity) != null) dropLocked(identity) }
        reconcile()
    }

    /** Re-reads the system per-app value; call from `onStart` and `onConfigurationChanged`. */
    fun check(activity: Activity) {
        foreground = WeakReference(activity)
        AppLocales.reassertDefault(appContext)
        scope.launch { reconcile() }
    }

    /**
     * The picker: sets the active profile's language ([tag] null is Match
     * device). It applies at once and saves in the background.
     */
    fun choose(activity: Activity, tag: String?) {
        foreground = WeakReference(activity)
        scope.launch {
            val chosen = tag.orEmpty()
            val identity = lock.withLock {
                val identity = currentIdentity() ?: return@launch
                val decision = AppLocaleReconciler.choose(chosen, AppLocales.current(appContext), readLedger())
                applyLocked(decision, identity, Pending(profileWide = true, tag = chosen))
                publish(identity)
                identity
            }
            flushPending(identity, authorityFor(identity))
        }
    }

    private suspend fun reconcile() {
        val flushFor = lock.withLock {
            val identity = currentIdentity() ?: return
            val known = known?.takeIf { it.identity == identity }
            val pending = readPending(identity)
            val ledger = readLedger()
            val current = AppLocales.current(appContext)
            val userChanged = ledger != null && !ledger.applying && current != ledger.observed
            // Without the profile's value only a user change can be acted on.
            if (known == null && pending == null && !userChanged) return
            val stored = if (pending != null) pending.tag.ifEmpty { null } else known?.value
            val decision = AppLocaleReconciler.reconcile(
                current = current,
                override = stored?.let { AppLanguage.match(it, shipped) },
                ledger = ledger,
                shipped = shipped,
            )
            val writeBack = decision.writeBack
            when {
                writeBack == null -> applyLocked(decision, identity)
                // An older server can't hold it: as if refused.
                known?.supported == false -> dropLocked(identity)
                else -> applyLocked(decision, identity, Pending(profileWide = false, tag = writeBack))
            }
            publish(identity)
            identity.takeIf { writeBack != null && known?.supported != false }
        } ?: return
        flushPending(flushFor, authorityFor(flushFor))
    }

    /**
     * Commits the ledger together with the profile's [pending] choice (when
     * given), so a crash can't leave one without the other. An apply is
     * recorded as in flight first, so a crash mid-apply is not read as a user
     * choice.
     */
    private fun applyLocked(decision: AppLocaleReconciler.Decision, identity: String, pending: Pending? = null) {
        val tags = decision.apply ?: return commit(decision.ledger, identity, pending)
        commit(decision.ledger.copy(applying = true), identity, pending)
        val recreate = AppLocales.apply(appContext, tags)
        commit(decision.ledger)
        if (recreate) foreground?.get()?.let { activity -> activity.runOnUiThread { activity.recreate() } }
    }

    /**
     * Drops the profile's unsent choice and the ledger: the system value
     * becomes the user's own, and the profile's value applies over it.
     */
    private fun dropLocked(identity: String) = prefs.edit(commit = true) {
        remove(KEY_OBSERVED)
        remove(KEY_BASELINE)
        remove(KEY_APPLYING)
        remove(PENDING_PREFIX + identity)
    }

    private suspend fun flushPending(identity: String, authority: AuthScopeSnapshot?) {
        if (authority == null) return
        val refused = flushLock.withLock {
            val raw = prefs.getString(PENDING_PREFIX + identity, null) ?: return
            val pending = Pending.decode(raw) ?: return
            val value = if (pending.tag.isEmpty()) JsonNull else JsonPrimitive(pending.tag)
            val result = if (pending.profileWide) {
                when (val saved = repository.setProfileValue(AppLanguage.KEY, value, authority)) {
                    is ApiResult.Success -> repository.clearProfileDeviceValue(AppLanguage.KEY, authority)
                    is ApiResult.Error -> saved
                    is ApiResult.NetworkError -> saved
                }
            } else {
                repository.setProfileDeviceValue(AppLanguage.KEY, value, authority)
            }
            when (result) {
                is ApiResult.Success -> {
                    writes += 1
                    known = known?.let { if (it.identity == identity) it.copy(value = pending.tag.ifEmpty { null }) else it }
                    lock.withLock {
                        if (prefs.getString(PENDING_PREFIX + identity, null) == raw) {
                            prefs.edit(commit = true) { remove(PENDING_PREFIX + identity) }
                        }
                    }
                    false
                }
                // Not sent: another profile is active now. It goes when this one is back.
                is ApiResult.Error -> if (result.code == 0 && result.error == IDENTITY_CHANGED) {
                    false
                } else if (isTransientSettingsWrite(result.code)) {
                    false
                } else {
                    // Refused for good, unless a newer choice replaced it meanwhile.
                    lock.withLock {
                        val unchanged = prefs.getString(PENDING_PREFIX + identity, null) == raw
                        if (unchanged) dropLocked(identity)
                        unchanged
                    }
                }
                is ApiResult.NetworkError -> false
            }
        }
        if (refused) reconcile()
    }

    private suspend fun authorityFor(identity: String): AuthScopeSnapshot? =
        known?.takeIf { it.identity == identity }?.authority
            ?: captureAuthority()?.takeIf { it.belongsTo(identity) }

    private fun publish(identity: String) {
        val known = known?.takeIf { it.identity == identity }
        val pending = readPending(identity)
        _state.value = AppLanguageState(
            supported = known?.supported == true,
            choice = if (pending != null) pending.tag.ifEmpty { null } else known?.value,
            languages = shipped,
        )
    }

    private fun readLedger(): Ledger? {
        val observed = prefs.getString(KEY_OBSERVED, null) ?: return null
        return Ledger(
            observed = observed,
            baseline = prefs.getString(KEY_BASELINE, null),
            applying = prefs.getBoolean(KEY_APPLYING, false),
        )
    }

    private fun commit(ledger: Ledger, identity: String? = null, pending: Pending? = null) = prefs.edit(commit = true) {
        putString(KEY_OBSERVED, ledger.observed)
        if (ledger.baseline == null) remove(KEY_BASELINE) else putString(KEY_BASELINE, ledger.baseline)
        putBoolean(KEY_APPLYING, ledger.applying)
        if (identity != null && pending != null) putString(PENDING_PREFIX + identity, pending.encode())
    }

    private fun readPending(identity: String): Pending? = Pending.decode(prefs.getString(PENDING_PREFIX + identity, null))

    private suspend fun currentIdentity(): String? = AccountScopedKeys.readStable(getActiveAccountId) { accountId ->
        val profileId = getActiveProfileId()?.takeIf { it.isNotBlank() } ?: return@readStable null
        AccountScopedKeys.identity(getServerUrl().orEmpty(), accountId, profileId)
    }

    /** Requests pinned to this scope land on [identity]'s server, account and profile. */
    private fun AuthScopeSnapshot.belongsTo(identity: String): Boolean {
        val profile = profileId ?: return false
        return identity.startsWith("$serverUrl|") && identity.endsWith("|$profile") &&
            (accountId == null || accountId in AccountScopedKeys.accountIdsIn(identity))
    }

    private companion object {
        const val PREFS = "silo_app_language"
        const val KEY_OBSERVED = "ledger_observed"
        const val KEY_BASELINE = "ledger_baseline"
        const val KEY_APPLYING = "ledger_applying"
        const val PENDING_PREFIX = "pending|"
        const val IDENTITY_CHANGED = "identity_changed"
        const val READ_ATTEMPTS = 3
    }
}
