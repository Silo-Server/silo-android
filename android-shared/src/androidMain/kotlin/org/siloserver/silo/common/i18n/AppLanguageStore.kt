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
import org.siloserver.silo.common.store.AccountScopedKeys
import org.siloserver.silo.model.settings.AppLanguage
import org.siloserver.silo.model.settings.AppLocaleReconciler
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
 */
data class AppLanguageState(val supported: Boolean = false, val choice: String? = null)

/**
 * Applies the active profile's `ui.app_language` to the per-app language, for
 * both the phone and TV apps. On Android 13+ the profile's value and the
 * system per-app language are one knob: a language picked in system settings
 * is saved as this device's value for the active profile.
 *
 * - Refreshes on a server/account/profile change, on foreground and on
 *   reconnect (`ServerDrivenConfigRefresher`), then reconciles.
 * - [check] runs from each activity's `onStart` and `onConfigurationChanged`,
 *   so a change made in system settings is seen even while the activity stays
 *   started (picture-in-picture, split screen), and before the server answers.
 * - A user choice, from system settings or [choose], is queued per profile in
 *   preferences (unlike `ServerSettingsFlusher`'s in-memory queue, it must
 *   survive process death) and retried until the server takes it. While it
 *   waits it is that profile's value, so a stale server answer can't re-apply
 *   over it. A choice the server can't store (an older server, a rejected
 *   value) stays as the device's own language instead.
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

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Guards the ledger, the pending values and every apply. */
    private val lock = Mutex()

    /** One PUT at a time, so they reach the server in the order chosen. */
    private val flushLock = Mutex()

    private val _state = MutableStateFlow(AppLanguageState())
    val state: StateFlow<AppLanguageState> = _state.asStateFlow()

    @Volatile private var known: Known? = null

    /** Bumped by every write that lands; a read that began before one is stale. */
    @Volatile private var writes = 0L

    /** Before 13 an apply only lands through a recreation of this activity. */
    @Volatile private var foreground: WeakReference<Activity>? = null

    init {
        scope.launch {
            AppLocales.migrateLegacyTag(appContext)
            identityChanges.collectLatest {
                known = null
                _state.value = AppLanguageState()
                refresh()
            }
        }
    }

    suspend fun refresh() {
        val identity = currentIdentity() ?: return
        val authority = captureAuthority()?.takeIf { it.belongsTo(identity) } ?: return
        val supported = when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success -> AppLanguage.isSupported(caps.data)
            // No contract routes: an older server.
            is ApiResult.Error -> if (caps.code == 404) false else return
            is ApiResult.NetworkError -> return
        }
        if (supported) flushPending(identity, authority)
        val readStartedAt = writes
        val value = if (!supported) {
            null
        } else {
            when (val result = repository.getEffectiveValues(listOf(AppLanguage.KEY), authority = authority)) {
                is ApiResult.Success ->
                    runCatching { result.data[AppLanguage.KEY]?.value?.jsonPrimitive?.contentOrNull }.getOrNull()
                is ApiResult.Error, is ApiResult.NetworkError -> return
            }
        }
        if (currentIdentity() != identity) return
        // A write landed while this read was in flight: the value it saved wins.
        val previous = known?.takeIf { it.identity == identity }
        val fresh = if (writes != readStartedAt && previous != null) previous.value else value
        known = Known(identity, authority, supported, fresh)
        if (!supported) lock.withLock { readPending(identity)?.let { disownLocked(identity, it) } }
        reconcile()
    }

    /** Re-reads the system per-app value; call from `onStart` and `onConfigurationChanged`. */
    fun check(activity: Activity) {
        foreground = WeakReference(activity)
        AppLocales.reassertDefault(appContext)
        scope.launch { reconcile() }
    }

    /**
     * The picker: sets this device's language for the active profile ([tag]
     * null is Match device). It applies at once and saves in the background.
     */
    fun choose(activity: Activity, tag: String?) {
        foreground = WeakReference(activity)
        scope.launch {
            val chosen = tag.orEmpty()
            val identity = lock.withLock {
                val identity = currentIdentity() ?: return@launch
                // Queued first, so a process death mid-apply can't lose the choice.
                writePending(identity, chosen)
                applyLocked(AppLocaleReconciler.choose(chosen, AppLocales.current(appContext), readLedger()))
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
            val stored = if (pending != null) pending.ifEmpty { null } else known?.value
            val decision = AppLocaleReconciler.reconcile(current, stored?.let { AppLanguage.match(it) }, ledger)
            applyLocked(decision)
            val writeBack = decision.writeBack
            val queued = when {
                writeBack == null -> false
                known?.supported == false -> { disownLocked(identity, writeBack); false }
                else -> { writePending(identity, writeBack); true }
            }
            publish(identity)
            identity.takeIf { queued }
        } ?: return
        flushPending(flushFor, authorityFor(flushFor))
    }

    /** Records the target as in flight first, so a crash mid-apply is not read as a user choice. */
    private fun applyLocked(decision: AppLocaleReconciler.Decision) {
        val tags = decision.apply ?: return writeLedger(decision.ledger)
        writeLedger(decision.ledger.copy(applying = true))
        val recreate = AppLocales.apply(appContext, tags)
        writeLedger(decision.ledger)
        if (recreate) foreground?.get()?.let { activity -> activity.runOnUiThread { activity.recreate() } }
    }

    /**
     * The server can't store [tag] for this profile: it stays the device's own
     * language rather than an override the app would undo on the next pass.
     */
    private fun disownLocked(identity: String, tag: String) {
        clearPending(identity)
        val ledger = readLedger() ?: return
        if (ledger.observed.substringBefore(',') == tag) writeLedger(ledger.copy(baseline = null))
    }

    private suspend fun flushPending(identity: String, authority: AuthScopeSnapshot?) {
        if (authority == null) return
        flushLock.withLock {
            val pending = readPending(identity) ?: return
            val value = if (pending.isEmpty()) JsonNull else JsonPrimitive(pending)
            when (val result = repository.setProfileDeviceValue(AppLanguage.KEY, value, authority)) {
                is ApiResult.Success -> {
                    writes += 1
                    known = known?.let { if (it.identity == identity) it.copy(value = pending.ifEmpty { null }) else it }
                    lock.withLock { if (readPending(identity) == pending) clearPending(identity) }
                }
                // A value the server refuses will never land; keep retrying only transient failures.
                is ApiResult.Error -> if (result.code in REJECTED) {
                    lock.withLock { if (readPending(identity) == pending) disownLocked(identity, pending) }
                }
                is ApiResult.NetworkError -> Unit
            }
        }
    }

    private suspend fun authorityFor(identity: String): AuthScopeSnapshot? =
        known?.takeIf { it.identity == identity }?.authority
            ?: captureAuthority()?.takeIf { it.belongsTo(identity) }

    private fun publish(identity: String) {
        val known = known?.takeIf { it.identity == identity }
        val pending = readPending(identity)
        _state.value = AppLanguageState(
            supported = known?.supported == true,
            choice = if (pending != null) pending.ifEmpty { null } else known?.value,
        )
    }

    private fun readLedger(): AppLocaleReconciler.Ledger? {
        val observed = prefs.getString(KEY_OBSERVED, null) ?: return null
        return AppLocaleReconciler.Ledger(
            observed = observed,
            baseline = prefs.getString(KEY_BASELINE, null),
            applying = prefs.getBoolean(KEY_APPLYING, false),
        )
    }

    private fun writeLedger(ledger: AppLocaleReconciler.Ledger) = prefs.edit(commit = true) {
        putString(KEY_OBSERVED, ledger.observed)
        if (ledger.baseline == null) remove(KEY_BASELINE) else putString(KEY_BASELINE, ledger.baseline)
        putBoolean(KEY_APPLYING, ledger.applying)
    }

    private fun readPending(identity: String): String? = prefs.getString(PENDING_PREFIX + identity, null)
    private fun writePending(identity: String, tag: String) = prefs.edit(commit = true) { putString(PENDING_PREFIX + identity, tag) }
    private fun clearPending(identity: String) = prefs.edit(commit = true) { remove(PENDING_PREFIX + identity) }

    private suspend fun currentIdentity(): String? = AccountScopedKeys.readStable(getActiveAccountId) { accountId ->
        val profileId = getActiveProfileId()?.takeIf { it.isNotBlank() } ?: return@readStable null
        AccountScopedKeys.identity(getServerUrl().orEmpty(), accountId, profileId)
    }

    /** Requests pinned to this scope land on [identity]'s profile. */
    private fun AuthScopeSnapshot.belongsTo(identity: String): Boolean =
        profileId?.let { identity.endsWith("|$it") } == true

    private companion object {
        const val PREFS = "silo_app_language"
        const val KEY_OBSERVED = "ledger_observed"
        const val KEY_BASELINE = "ledger_baseline"
        const val KEY_APPLYING = "ledger_applying"
        const val PENDING_PREFIX = "pending_device_value|"
        val REJECTED = setOf(400, 404, 422)
    }
}
