package org.siloserver.silo.common.di

import android.content.SharedPreferences
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RecentPartyOwner
import org.siloserver.silo.watchtogether.RecentWatchParties
import org.siloserver.silo.watchtogether.SharedPreferencesRecentWatchPartyStorage
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyRecentsRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * Watch Party wiring shared by the phone and TV apps: capability discovery
 * and the recent-party store. The room owner itself lives in the shared
 * repository module.
 */
val watchPartyModule = module {
    single {
        val tokens = get<TokenManager>()
        val playback = get<PlaybackV2Api>()
        val api = get<WatchTogetherApi>()
        WatchPartyAvailabilityRepository(
            roomCapabilities = { scope -> api.capabilities(scope) },
            playbackCapabilities = { scope -> playback.capabilities(scope) },
            authScopeProvider = { tokens.snapshotCurrentScope() },
        )
    }
    single {
        val authorities = get<TokenManager>() as? DurableLoginAuthorityProvider
        RecentWatchParties(
            // The app-level SharedPreferences binding is the encrypted store.
            storage = SharedPreferencesRecentWatchPartyStorage(get<SharedPreferences>()),
            owner = {
                authorities?.snapshotDurableLoginAuthority()?.let { authority ->
                    val serverId = authority.scope.serverId
                    val profileId = authority.scope.profileId
                    if (serverId.isBlank() || profileId.isNullOrBlank()) null
                    else RecentPartyOwner(serverId, authority.loginId, profileId)
                }
            },
        )
    }
    single(createdAtStart = true) {
        val repository = get<WatchTogetherRepository>()
        WatchPartyRecentsRecorder(
            recents = get(),
            room = repository.roomSnapshot,
            ended = repository.ended,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            identityTransitions = get(),
        )
    }
}
