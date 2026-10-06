package org.siloserver.silo.tv.data.preferences

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Profile Selection timing rules, mirroring silo-apple
 * `ProfileLaunchPolicyTests`: when Who's Watching is required, and when the
 * launcher's Watch Next row may show the active profile.
 */
class TvProfileLaunchPreferencesTest {

    private val hour = 60 * 60 * 1000L
    private val start = 1_000_000_000L

    private fun state(behavior: ProfileLaunchBehavior, backgroundedAt: Long? = null) =
        ProfileLaunchState(behavior = behavior, backgroundedAtMs = backgroundedAt)

    @Test
    fun `missing or unknown stored value means Automatic`() {
        assertEquals(ProfileLaunchBehavior.Automatic, ProfileLaunchBehavior.fromRaw(null))
        assertEquals(ProfileLaunchBehavior.Automatic, ProfileLaunchBehavior.fromRaw("later"))
        assertEquals(ProfileLaunchBehavior.EveryTime, ProfileLaunchBehavior.fromRaw("askEveryLaunch"))
    }

    @Test
    fun `Automatic never asks and never records an away interval`() {
        val marked = state(ProfileLaunchBehavior.Automatic).markedBackgrounded(start)
        assertNull(marked.backgroundedAtMs)
        assertFalse(marked.requiresSelectionAtLaunch(start + 100 * hour))
        assertFalse(marked.requiresSelectionAfterBackground(start + 100 * hour))
        assertTrue(marked.allowsWatchNext(start + 100 * hour))
    }

    @Test
    fun `Every Time asks at every launch and every real return, and hides Watch Next`() {
        val everyTime = state(ProfileLaunchBehavior.EveryTime)
        // A process that died without recording a background still asks.
        assertTrue(everyTime.requiresSelectionAtLaunch(start))
        // A return needs a recorded background, so a brief pause never locks.
        assertFalse(everyTime.requiresSelectionAfterBackground(start))
        assertTrue(everyTime.markedBackgrounded(start).requiresSelectionAfterBackground(start))
        assertFalse(everyTime.allowsWatchNext(start))
    }

    @Test
    fun `After 1 Hour asks exactly when the hour has passed`() {
        val away = state(ProfileLaunchBehavior.AfterOneHour, backgroundedAt = start)
        assertFalse(away.requiresSelectionAfterBackground(start + hour - 1))
        assertTrue(away.requiresSelectionAfterBackground(start + hour))
        assertFalse(away.requiresSelectionAtLaunch(start + hour - 1))
        assertTrue(away.requiresSelectionAtLaunch(start + hour))
        // Without a recorded away interval a timed choice restores.
        assertFalse(state(ProfileLaunchBehavior.AfterOneHour).requiresSelectionAtLaunch(start + 100 * hour))
    }

    @Test
    fun `After 12 Hours asks exactly when twelve hours have passed`() {
        val away = state(ProfileLaunchBehavior.AfterTwelveHours, backgroundedAt = start)
        assertFalse(away.requiresSelectionAfterBackground(start + 12 * hour - 1))
        assertTrue(away.requiresSelectionAfterBackground(start + 12 * hour))
    }

    @Test
    fun `a clock behind the recorded start asks instead of trusting it`() {
        val away = state(ProfileLaunchBehavior.AfterTwelveHours, backgroundedAt = start)
        assertTrue(away.requiresSelectionAtLaunch(start - 1))
        assertTrue(away.requiresSelectionAfterBackground(start - 1))
    }

    @Test
    fun `timed choices keep Watch Next until the interval runs out`() {
        val away = state(ProfileLaunchBehavior.AfterOneHour, backgroundedAt = start)
        assertTrue(away.allowsWatchNext(start + hour - 1))
        assertFalse(away.allowsWatchNext(start + hour))
        assertTrue(state(ProfileLaunchBehavior.AfterOneHour).allowsWatchNext(start + 100 * hour))
    }

    @Test
    fun `switching to Automatic ends a running away interval`() {
        val away = state(ProfileLaunchBehavior.EveryTime, backgroundedAt = start)
        assertNull(away.withBehavior(ProfileLaunchBehavior.Automatic).backgroundedAtMs)
        assertEquals(start, away.withBehavior(ProfileLaunchBehavior.AfterOneHour).backgroundedAtMs)
    }

    @Test
    fun `the choice and the away interval survive a process restart`() {
        val prefs = InMemoryPrefs()
        var now = start
        val first = TvProfileLaunchPreferences(prefs) { now }
        first.setBehavior(ProfileLaunchBehavior.AfterOneHour)
        first.markBackgrounded()

        now = start + hour
        val restarted = TvProfileLaunchPreferences(prefs) { now }
        assertEquals(ProfileLaunchBehavior.AfterOneHour, restarted.state.value.behavior)
        assertEquals(start, restarted.state.value.backgroundedAtMs)
        assertTrue(restarted.requiresSelectionAtLaunch())

        restarted.clearBackgroundedAt()
        assertFalse(TvProfileLaunchPreferences(prefs) { now }.requiresSelectionAtLaunch())
    }
}

/** Just enough SharedPreferences for the store: strings and longs. */
private class InMemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removed += key
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            removed.forEach(values::remove)
            values.putAll(pending)
        }
    }
}
