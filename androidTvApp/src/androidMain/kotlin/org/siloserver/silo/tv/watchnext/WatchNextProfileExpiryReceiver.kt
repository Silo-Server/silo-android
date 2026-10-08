package org.siloserver.silo.tv.watchnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.context.GlobalContext
import org.siloserver.silo.tv.data.preferences.TvProfileLaunchPreferences

/**
 * Keeps the launcher's Watch Next row in line with Profile Selection while
 * Silo is away, at the moments nothing in Silo is running to do it:
 *
 * - the wake-up alarm [WatchNextSeeder.scheduleProfileExpiryCheck] sets for
 *   when a timed choice runs out. JobScheduler never wakes a TV in standby
 *   and can defer work for an app not used lately, so a WorkManager job
 *   alone could leave the previous viewer's titles up well past the limit;
 * - a reboot, which clears that alarm;
 * - a clock change, which can end the away interval early (a clock set back
 *   behind its start counts as expired).
 *
 * Each removes the row when the setting now hides it, and otherwise re-arms
 * the alarm for an interval still running.
 */
class WatchNextProfileExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val koin = GlobalContext.get()
        val preferences = koin.get<TvProfileLaunchPreferences>()
        val seeder = koin.get<WatchNextSeeder>()
        // This runs on the main thread, as choosing a profile does, so a
        // choice cannot land between this check and the wipe, and the seed
        // that choice starts waits for the wipe to finish.
        if (preferences.allowsWatchNext()) {
            preferences.state.value.awayExpiresAtMs?.let(seeder::scheduleProfileExpiryCheck)
            return
        }
        val pending = goAsync()
        // Keep the hourly refresh: a clock set back and then corrected can
        // make the same profile's titles allowed again.
        seeder.clear(keepRefresh = true).invokeOnCompletion { cause ->
            if (cause == null) {
                Log.i(TAG, "Profile Selection hides Watch Next; removed the row")
            } else {
                Log.w(TAG, "Watch Next wipe failed", cause)
            }
            pending.finish()
        }
    }

    companion object {
        /** The expiry alarm's own action; the others come from the system. */
        const val ACTION_PROFILE_EXPIRY = "org.siloserver.silo.tv.action.WATCH_NEXT_PROFILE_EXPIRY"

        private const val TAG = "WatchNextExpiry"
        private val HANDLED_ACTIONS = setOf(
            ACTION_PROFILE_EXPIRY,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
