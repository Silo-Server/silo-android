package org.siloserver.silo.tv.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When this Android TV asks "Who's watching?" instead of reopening the last
 * profile. Mirrors silo-apple `ProfileLaunchBehavior` (tvOS General → PROFILE
 * AT LAUNCH → Profile Selection), including its titles and descriptions.
 */
enum class ProfileLaunchBehavior(
    /** Stored value. Same strings as silo-apple's raw values. */
    val raw: String,
    val title: String,
    val description: String,
    /** How long Silo can be away before the picker shows again; null for the untimed choices. */
    val awayTimeoutMs: Long?,
) {
    Automatic(
        raw = "automatic",
        title = "Automatic",
        description = "Use the last profile selected on this Android TV. " +
            "A protected profile can reopen without asking for its PIN.",
        awayTimeoutMs = null,
    ),
    EveryTime(
        raw = "askEveryLaunch",
        title = "Every Time",
        description = "Show Who's Watching whenever you return to Silo.",
        awayTimeoutMs = null,
    ),
    AfterOneHour(
        raw = "afterOneHour",
        title = "After 1 Hour",
        description = "Show Who's Watching when you've been away from Silo for 1 hour.",
        awayTimeoutMs = 60 * 60 * 1000L,
    ),
    AfterTwelveHours(
        raw = "afterTwelveHours",
        title = "After 12 Hours",
        description = "Show Who's Watching when you've been away from Silo for 12 hours.",
        awayTimeoutMs = 12 * 60 * 60 * 1000L,
    ),
    ;

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
     * Whether the launcher's Watch Next row may show the active profile's
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

    /** Automatic never keeps an away interval. */
    fun withBehavior(behavior: ProfileLaunchBehavior): ProfileLaunchState = copy(
        behavior = behavior,
        backgroundedAtMs = if (behavior == ProfileLaunchBehavior.Automatic) null else backgroundedAtMs,
    )

    fun markedBackgrounded(atMs: Long): ProfileLaunchState =
        if (behavior == ProfileLaunchBehavior.Automatic) copy(backgroundedAtMs = null) else copy(backgroundedAtMs = atMs)

    private fun hasExpiredAwayInterval(nowMs: Long): Boolean {
        val since = backgroundedAtMs ?: return false
        val timeout = behavior.awayTimeoutMs ?: return false
        // A clock behind the recorded start (a TV that booted before its time
        // synced, or a manual change) cannot show how long Silo was away, so
        // ask rather than keep the profile open indefinitely.
        if (nowMs < since) return true
        return nowMs - since >= timeout
    }
}

/**
 * Device-wide Profile Selection setting. It is never sent to the server, so a
 * choice on this TV leaves every other device alone (silo-apple
 * profile-launch design §12). It survives sign-out; the profile itself does
 * not.
 *
 * Backed by SharedPreferences rather than DataStore: `onStop` writes the away
 * marker and `onStart` reads it straight after, and SharedPreferences makes
 * that write visible synchronously.
 */
class TvProfileLaunchPreferences(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProfileLaunchState> = _state.asStateFlow()

    fun requiresSelectionAtLaunch(): Boolean = state.value.requiresSelectionAtLaunch(clock())

    fun requiresSelectionAfterBackground(): Boolean = state.value.requiresSelectionAfterBackground(clock())

    fun allowsWatchNext(): Boolean = state.value.allowsWatchNext(clock())

    fun setBehavior(behavior: ProfileLaunchBehavior) = update { it.withBehavior(behavior) }

    /** Start the away clock. A no-op under Automatic. */
    fun markBackgrounded() = update { it.markedBackgrounded(clock()) }

    /** End the away interval: Silo is in use again with a chosen profile. */
    fun clearBackgroundedAt() = update { it.copy(backgroundedAtMs = null) }

    @Synchronized
    private fun update(change: (ProfileLaunchState) -> ProfileLaunchState) {
        val next = change(_state.value)
        if (next == _state.value) return
        prefs.edit {
            putString(KEY_BEHAVIOR, next.behavior.raw)
            val at = next.backgroundedAtMs
            if (at == null) remove(KEY_BACKGROUNDED_AT) else putLong(KEY_BACKGROUNDED_AT, at)
        }
        _state.value = next
    }

    private fun read(): ProfileLaunchState = ProfileLaunchState(
        behavior = ProfileLaunchBehavior.fromRaw(prefs.getString(KEY_BEHAVIOR, null)),
        backgroundedAtMs = if (prefs.contains(KEY_BACKGROUNDED_AT)) prefs.getLong(KEY_BACKGROUNDED_AT, 0L) else null,
    )

    private companion object {
        const val PREFS_NAME = "silo_tv_profile_launch"
        const val KEY_BEHAVIOR = "behavior"
        const val KEY_BACKGROUNDED_AT = "backgrounded_at_ms"
    }
}
