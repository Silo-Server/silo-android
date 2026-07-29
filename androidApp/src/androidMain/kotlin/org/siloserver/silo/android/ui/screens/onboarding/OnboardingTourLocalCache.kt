package org.siloserver.silo.android.ui.screens.onboarding

import android.content.Context

/**
 * Local record of "this profile finished (or skipped) the tour", keyed per
 * server + profile. The server stays the source of truth — this only lets
 * the app skip the blocking state fetch on every profile selection once the
 * answer is known to be "done". It is set on a server-confirmed done state
 * or a locally initiated complete/skip, never on a failed check, so a
 * network error can't silence a tour that is still pending.
 */
class OnboardingTourLocalCache(context: Context) {

    private val prefs =
        context.getSharedPreferences("onboarding_tour", Context.MODE_PRIVATE)

    private fun key(serverId: String?, profileId: String?): String? {
        if (serverId.isNullOrBlank() || profileId.isNullOrBlank()) return null
        return "done:$serverId:$profileId"
    }

    fun isDone(serverId: String?, profileId: String?): Boolean =
        key(serverId, profileId)?.let { prefs.getBoolean(it, false) } ?: false

    fun markDone(serverId: String?, profileId: String?) {
        key(serverId, profileId)?.let { prefs.edit().putBoolean(it, true).apply() }
    }
}
