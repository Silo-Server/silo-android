package org.siloserver.silo.common.profiles

import android.content.SharedPreferences
import org.siloserver.silo.common.player.ActivePlayerHolder
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
class ProfileLaunchPreferencesTest {

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
    fun `the Watch Next expiry check is due when the away interval runs out`() {
        assertEquals(start + hour, state(ProfileLaunchBehavior.AfterOneHour, backgroundedAt = start).awayExpiresAtMs)
        assertEquals(start + 12 * hour, state(ProfileLaunchBehavior.AfterTwelveHours, backgroundedAt = start).awayExpiresAtMs)
        // Nothing times out while Silo is in use, or for the untimed choices.
        assertNull(state(ProfileLaunchBehavior.AfterOneHour).awayExpiresAtMs)
        assertNull(state(ProfileLaunchBehavior.EveryTime, backgroundedAt = start).awayExpiresAtMs)
        assertNull(state(ProfileLaunchBehavior.Automatic).markedBackgrounded(start).awayExpiresAtMs)
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
        val first = ProfileLaunchPreferences(prefs) { now }
        first.setBehavior(ProfileLaunchBehavior.AfterOneHour)
        first.markBackgrounded()

        now = start + hour
        val restarted = ProfileLaunchPreferences(prefs) { now }
        assertEquals(ProfileLaunchBehavior.AfterOneHour, restarted.state.value.behavior)
        assertEquals(start, restarted.state.value.backgroundedAtMs)
        assertTrue(restarted.requiresSelectionAtLaunch())

        restarted.clearBackgroundedAt()
        assertFalse(ProfileLaunchPreferences(prefs) { now }.requiresSelectionAtLaunch())
    }

    @Test
    fun `a new TV asks who's watching and offers to remember the first profile`() {
        val prefs = InMemoryPrefs()
        val store = ProfileLaunchPreferences(prefs) { start }
        store.settleFirstLaunch(hasRememberedProfile = false)

        assertEquals(ProfileLaunchBehavior.EveryTime, store.state.value.behavior)
        assertTrue(store.isRememberOfferPending)
        assertTrue(store.requiresSelectionAtLaunch())
        // Later launches keep what was saved.
        val next = ProfileLaunchPreferences(prefs) { start }
        next.settleFirstLaunch(hasRememberedProfile = true)
        assertEquals(ProfileLaunchBehavior.EveryTime, next.state.value.behavior)
        assertTrue(next.isRememberOfferPending)
    }

    @Test
    fun `a TV that already opens a profile keeps Automatic and gets no offer`() {
        val prefs = InMemoryPrefs()
        ProfileLaunchPreferences(prefs) { start }.settleFirstLaunch(hasRememberedProfile = true)

        val restarted = ProfileLaunchPreferences(prefs) { start }
        assertEquals(ProfileLaunchBehavior.Automatic, restarted.state.value.behavior)
        assertFalse(restarted.isRememberOfferPending)
        assertFalse(restarted.requiresSelectionAtLaunch())
    }

    @Test
    fun `the offer is answered once and the answer is kept`() {
        for (remember in listOf(true, false)) {
            val prefs = InMemoryPrefs()
            ProfileLaunchPreferences(prefs) { start }.apply {
                settleFirstLaunch(hasRememberedProfile = false)
                answerRememberOffer(remember)
            }
            val restarted = ProfileLaunchPreferences(prefs) { start }
            assertFalse(restarted.isRememberOfferPending)
            assertEquals(
                if (remember) ProfileLaunchBehavior.Automatic else ProfileLaunchBehavior.EveryTime,
                restarted.state.value.behavior,
            )
        }
    }

    @Test
    fun `choosing in Settings ends the offer`() {
        val prefs = InMemoryPrefs()
        val store = ProfileLaunchPreferences(prefs) { start }
        store.settleFirstLaunch(hasRememberedProfile = false)
        store.setBehavior(ProfileLaunchBehavior.AfterOneHour)
        assertFalse(ProfileLaunchPreferences(prefs) { start }.isRememberOfferPending)
    }

    @Test
    fun `a required choice starts at Change Account once several accounts are saved`() {
        data class Case(
            val required: Boolean,
            val accounts: Int,
            val onlyProfile: Boolean,
            val expected: ProfileSelectionPrompt,
        )
        listOf(
            // Not required (Automatic, or a timed choice still running): the
            // profile stays, however many accounts are saved.
            Case(required = false, accounts = 1, onlyProfile = false, expected = ProfileSelectionPrompt.None),
            Case(required = false, accounts = 3, onlyProfile = true, expected = ProfileSelectionPrompt.None),
            // One account: its only profile without a PIN stays open;
            // otherwise the picker asks.
            Case(required = true, accounts = 1, onlyProfile = true, expected = ProfileSelectionPrompt.None),
            Case(required = true, accounts = 1, onlyProfile = false, expected = ProfileSelectionPrompt.WhoIsWatching),
            // Several accounts: Change Account, even when the open one has a
            // single profile without a PIN.
            Case(required = true, accounts = 2, onlyProfile = true, expected = ProfileSelectionPrompt.ChangeAccount),
            Case(required = true, accounts = 3, onlyProfile = false, expected = ProfileSelectionPrompt.ChangeAccount),
        ).forEach { case ->
            assertEquals(
                case.expected,
                profileSelectionPrompt(
                    selectionRequired = case.required,
                    savedAccounts = case.accounts,
                    onlyProfileNeedsNoChoice = case.onlyProfile,
                ),
                case.toString(),
            )
        }
    }

    @Test
    fun `Cast that stops while Silo is hidden starts the away clock, so Every Time asks`() {
        val store = ProfileLaunchPreferences(InMemoryPrefs()) { start }
        store.setBehavior(ProfileLaunchBehavior.EveryTime)
        var casting = true
        val tracker = ProfileAwayTracker(store, ActivePlayerHolder(), isCasting = { casting })

        tracker.onBackground()
        assertNull(store.state.value.backgroundedAtMs)

        casting = false
        tracker.onRemotePlaybackChanged()
        assertEquals(start, store.state.value.backgroundedAtMs)

        tracker.onForeground()
        assertTrue(tracker.selectionRequired.value)
    }

    @Test
    fun `a Cast change while Silo is in use starts no away clock`() {
        val store = ProfileLaunchPreferences(InMemoryPrefs()) { start }
        store.setBehavior(ProfileLaunchBehavior.EveryTime)
        val tracker = ProfileAwayTracker(store, ActivePlayerHolder(), isCasting = { false })

        tracker.onRemotePlaybackChanged()
        assertNull(store.state.value.backgroundedAtMs)
    }
}

/** Just enough SharedPreferences for the store: strings, longs and booleans. */
internal class InMemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
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
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
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
