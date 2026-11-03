package org.siloserver.silo.common.profiles

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.network.ServerRegistry

/**
 * When this device asks "Who's watching?" instead of reopening the last
 * profile. Mirrors silo-apple `ProfileLaunchBehavior` (General → Profile
 * Selection), including its titles and descriptions. Shared by the phone and
 * TV apps; each keeps its own choice on the device.
 */
enum class ProfileLaunchBehavior(
    /** Stored value. Same strings as silo-apple's raw values. */
    val raw: String,
    val title: String,
    private val describe: (device: String) -> String,
    /** How long Silo can be away before the picker shows again; null for the untimed choices. */
    val awayTimeoutMs: Long?,
) {
    Automatic(
        raw = "automatic",
        title = "Automatic",
        describe = { device ->
            "Use the last profile selected on $device. " +
                "A protected profile can reopen without asking for its PIN."
        },
        awayTimeoutMs = null,
    ),
    EveryTime(
        raw = "askEveryLaunch",
        title = "Every Time",
        describe = { "Show Who's Watching whenever you return to Silo." },
        awayTimeoutMs = null,
    ),
    AfterOneHour(
        raw = "afterOneHour",
        title = "After 1 Hour",
        describe = { "Show Who's Watching when you've been away from Silo for 1 hour." },
        awayTimeoutMs = 60 * 60 * 1000L,
    ),
    AfterTwelveHours(
        raw = "afterTwelveHours",
        title = "After 12 Hours",
        describe = { "Show Who's Watching when you've been away from Silo for 12 hours." },
        awayTimeoutMs = 12 * 60 * 60 * 1000L,
    ),
    ;

    /** The choice's description, naming [device] (for example "this Android TV") where it matters. */
    fun description(device: String): String = describe(device)

    companion object {
        /** Unknown or missing values fall back to today's behavior. */
        fun fromRaw(raw: String?): ProfileLaunchBehavior =
            entries.firstOrNull { it.raw == raw } ?: Automatic
    }
}

/**
 * The policy plus the start of the current interval away from Silo. Pure, so
 * the timing rules are unit-testable. Mirrors silo-apple `ProfileLaunchState`.
 */
data class ProfileLaunchState(
    val behavior: ProfileLaunchBehavior = ProfileLaunchBehavior.Automatic,
    /** Epoch ms when Silo last went to the background unused; null while in use. */
    val backgroundedAtMs: Long? = null,
    /**
     * Android TV only: a new TV offers the first profile picked on it as the
     * one to open automatically. True until that offer is answered or a choice is made in
     * Settings.
     */
    val rememberOfferPending: Boolean = false,
) {
    /**
     * Cold start. Every Time asks even when the previous process never
     * recorded a background; the timed choices ask only once a recorded away
     * interval has run out.
     */
    fun requiresSelectionAtLaunch(nowMs: Long): Boolean =
        behavior == ProfileLaunchBehavior.EveryTime || hasExpiredAwayInterval(nowMs)

    /** Warm return. Needs a recorded background, so a brief pause never locks. */
    fun requiresSelectionAfterBackground(nowMs: Long): Boolean {
        if (backgroundedAtMs == null) return false
        return behavior == ProfileLaunchBehavior.EveryTime || hasExpiredAwayInterval(nowMs)
    }

    /**
     * Android TV only. Whether the launcher's Watch Next row may show the active profile's
     * titles. silo-apple `TopShelfProfilePolicy`: never with Every Time, and
     * not once a timed choice has expired.
     */
    fun allowsWatchNext(nowMs: Long): Boolean =
        behavior != ProfileLaunchBehavior.EveryTime && !requiresSelectionAfterBackground(nowMs)

    /** When the current away interval runs out; null when nothing is timing out. */
    val awayExpiresAtMs: Long?
        get() {
            val since = backgroundedAtMs ?: return null
            val timeout = behavior.awayTimeoutMs ?: return null
            return since + timeout
        }

    /** Automatic never keeps an away interval. Choosing a behavior answers the offer. */
    fun withBehavior(behavior: ProfileLaunchBehavior): ProfileLaunchState = copy(
        behavior = behavior,
        backgroundedAtMs = if (behavior == ProfileLaunchBehavior.Automatic) null else backgroundedAtMs,
        rememberOfferPending = false,
    )

    /** The answer to the offer: remember opens the picked profile automatically from now on. */
    fun answeringRememberOffer(remember: Boolean): ProfileLaunchState = when {
        !rememberOfferPending -> this
        remember -> withBehavior(ProfileLaunchBehavior.Automatic)
        else -> copy(rememberOfferPending = false)
    }

    companion object {
        /**
         * Android TV only. The state of a TV with no saved choice. A new TV asks who's watching
         * and offers to remember the first profile picked; a TV that already
         * remembers a profile (it predates this setting) keeps opening it.
         */
        fun firstLaunch(hasRememberedProfile: Boolean): ProfileLaunchState =
            if (hasRememberedProfile) {
                ProfileLaunchState(behavior = ProfileLaunchBehavior.Automatic)
            } else {
                ProfileLaunchState(behavior = ProfileLaunchBehavior.EveryTime, rememberOfferPending = true)
            }
    }

    fun markedBackgrounded(atMs: Long): ProfileLaunchState =
        if (behavior == ProfileLaunchBehavior.Automatic) copy(backgroundedAtMs = null) else copy(backgroundedAtMs = atMs)

    private fun hasExpiredAwayInterval(nowMs: Long): Boolean {
        val since = backgroundedAtMs ?: return false
        val timeout = behavior.awayTimeoutMs ?: return false
        // A clock behind the recorded start (a device that booted before its time
        // synced, or a manual change) cannot show how long Silo was away, so
        // ask rather than keep the profile open indefinitely.
        if (nowMs < since) return true
        return nowMs - since >= timeout
    }
}

/**
 * One profile of one saved account on one server: what Automatic reopens
 * (multi-account spec §4.1).
 */
data class LaunchTile(
    val serverId: String,
    /** [AccountEntry.localAccountId]. */
    val accountId: String,
    val profileId: String,
)

/**
 * Device-wide Profile Selection setting. It is never sent to the server, so a
 * choice on this device leaves every other device alone (silo-apple
 * profile-launch design §12). It survives sign-out; the profile itself does
 * not.
 *
 * Backed by SharedPreferences rather than DataStore: `onStop` writes the away
 * marker and `onStart` reads it straight after, and SharedPreferences makes
 * that write visible synchronously.
 *
 * The phone and TV apps each pass their own file name. The TV's is
 * [TV_PREFS_NAME], unchanged from when this lived in the TV app, so choices
 * made there survive the move.
 *
 * Also holds the device-wide [lastTile], which Automatic reopens (spec §4.2).
 * Each account's remembered profile and its PIN proof are not kept here: they
 * live in that account's registry entry ([AccountEntry.profileId]) and
 * credential slot, so they already belong to the account and leave with it.
 */
class ProfileLaunchPreferences(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, prefsName: String) :
        this(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProfileLaunchState> = _state.asStateFlow()

    private val _lastTile = MutableStateFlow(readTile())

    /**
     * The tile last opened on this device, whichever server and account it
     * is on. Recorded by [trackActiveTile]; survives a sign-out of a
     * different account but not of its own ([retainAccounts]).
     */
    val lastTile: StateFlow<LaunchTile?> = _lastTile.asStateFlow()

    fun requiresSelectionAtLaunch(): Boolean = state.value.requiresSelectionAtLaunch(clock())

    fun requiresSelectionAfterBackground(): Boolean = state.value.requiresSelectionAfterBackground(clock())

    fun allowsWatchNext(): Boolean = state.value.allowsWatchNext(clock())

    fun setBehavior(behavior: ProfileLaunchBehavior) = update { it.withBehavior(behavior) }

    /**
     * Android TV only. Saves the starting choice the first time this TV runs
     * Silo; later launches keep what is saved. [hasRememberedProfile] says
     * whether the TV already opens a profile from an earlier version. The
     * phone never calls this, so it starts at Automatic with no offer.
     */
    @Synchronized
    fun settleFirstLaunch(hasRememberedProfile: Boolean) {
        if (prefs.contains(KEY_BEHAVIOR)) return
        write(ProfileLaunchState.firstLaunch(hasRememberedProfile))
    }

    val isRememberOfferPending: Boolean get() = state.value.rememberOfferPending

    fun answerRememberOffer(remember: Boolean) = update { it.answeringRememberOffer(remember) }

    /** Start the away clock. A no-op under Automatic. */
    fun markBackgrounded() = update { it.markedBackgrounded(clock()) }

    /** End the away interval: Silo is in use again with a chosen profile. */
    fun clearBackgroundedAt() = update { it.copy(backgroundedAtMs = null) }

    /** Records [tile] as the one Automatic reopens. */
    @Synchronized
    fun rememberTile(tile: LaunchTile) {
        if (_lastTile.value == tile) return
        prefs.edit {
            putString(KEY_TILE_SERVER, tile.serverId)
            putString(KEY_TILE_ACCOUNT, tile.accountId)
            putString(KEY_TILE_PROFILE, tile.profileId)
        }
        _lastTile.value = tile
    }

    @Synchronized
    fun forgetTile() {
        if (_lastTile.value == null && !prefs.contains(KEY_TILE_ACCOUNT)) return
        prefs.edit {
            remove(KEY_TILE_SERVER)
            remove(KEY_TILE_ACCOUNT)
            remove(KEY_TILE_PROFILE)
        }
        _lastTile.value = null
    }

    /**
     * The tile Automatic should reopen: [lastTile] while its account is still
     * saved on its server and, once the account's profiles are known, still
     * has that profile. Null means show the picker. Whether to ask at all is
     * [requiresSelectionAtLaunch]'s call; the picker also skips itself when
     * there is a single tile with no PIN (spec §4.2).
     */
    fun restorableTile(accounts: List<AccountEntry>): LaunchTile? {
        val tile = _lastTile.value ?: return null
        val account = accounts.firstOrNull { it.localAccountId == tile.accountId && it.serverId == tile.serverId }
            ?: return null
        if (account.cachedProfiles.isNotEmpty() && account.cachedProfiles.none { it.id == tile.profileId }) return null
        return tile
    }

    /** Sign-out deletes the account's launch memory: drop [lastTile] if its account is gone. */
    fun retainAccounts(accountIds: Set<String>) {
        val tile = _lastTile.value ?: return
        if (tile.accountId !in accountIds) forgetTile()
    }

    /**
     * Keeps [lastTile] on whatever tile is open: every time the active
     * server's account has a profile. Clearing the profile for the picker
     * leaves the last tile in place.
     */
    fun trackActiveTile(registry: ServerRegistry, scope: CoroutineScope): Job = scope.launch {
        registry.activeEntry.collect { entry ->
            val accountId = entry?.accountId ?: return@collect
            val profileId = entry.profileId?.takeIf { it.isNotBlank() } ?: return@collect
            rememberTile(LaunchTile(serverId = entry.id, accountId = accountId, profileId = profileId))
        }
    }

    @Synchronized
    private fun update(change: (ProfileLaunchState) -> ProfileLaunchState) {
        val next = change(_state.value)
        if (next == _state.value) return
        write(next)
    }

    private fun write(next: ProfileLaunchState) {
        prefs.edit {
            putString(KEY_BEHAVIOR, next.behavior.raw)
            val at = next.backgroundedAtMs
            if (at == null) remove(KEY_BACKGROUNDED_AT) else putLong(KEY_BACKGROUNDED_AT, at)
            if (next.rememberOfferPending) putBoolean(KEY_REMEMBER_OFFER, true) else remove(KEY_REMEMBER_OFFER)
        }
        _state.value = next
    }

    private fun readTile(): LaunchTile? {
        val serverId = prefs.getString(KEY_TILE_SERVER, null) ?: return null
        val accountId = prefs.getString(KEY_TILE_ACCOUNT, null) ?: return null
        val profileId = prefs.getString(KEY_TILE_PROFILE, null) ?: return null
        return LaunchTile(serverId, accountId, profileId)
    }

    private fun read(): ProfileLaunchState = ProfileLaunchState(
        behavior = ProfileLaunchBehavior.fromRaw(prefs.getString(KEY_BEHAVIOR, null)),
        backgroundedAtMs = if (prefs.contains(KEY_BACKGROUNDED_AT)) prefs.getLong(KEY_BACKGROUNDED_AT, 0L) else null,
        rememberOfferPending = prefs.getBoolean(KEY_REMEMBER_OFFER, false),
    )

    companion object {
        /** The Android TV app's file. */
        const val TV_PREFS_NAME = "silo_tv_profile_launch"

        /** The phone and tablet app's file. */
        const val PHONE_PREFS_NAME = "silo_profile_launch"

        private const val KEY_BEHAVIOR = "behavior"
        private const val KEY_BACKGROUNDED_AT = "backgrounded_at_ms"
        private const val KEY_REMEMBER_OFFER = "remember_offer_pending"
        private const val KEY_TILE_SERVER = "last_tile.server_id"
        private const val KEY_TILE_ACCOUNT = "last_tile.account_id"
        private const val KEY_TILE_PROFILE = "last_tile.profile_id"
    }
}
