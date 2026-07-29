package org.siloserver.silo.repository.port

import org.siloserver.silo.model.section.ResolvedSection

/** A cached home layout plus when it was captured (for an optional "offline" hint). */
data class HomeCacheSnapshot(
    val sections: List<ResolvedSection>,
    val cachedAtMs: Long,
)

data class HomeCacheWriteLease(val identityGeneration: Long)

/**
 * Offline read cache for the home screen (Track B). [HomeViewModel] serves the
 * cached layout instantly (stale-while-revalidate) so the app opens to content
 * with no network, then refreshes and re-caches on a successful fetch.
 *
 * Lives in commonMain so the shared ViewModel can depend on it; the Room-backed
 * implementation is Android-only and bound in the platform DI module. The
 * default no-op keeps commonMain/tests network-only.
 *
 * Cache is keyed by the active `(serverId, profileId)` inside the impl, and only
 * written for a *fully resolved* fetch — a partial refresh must not overwrite a
 * good cached home.
 */
interface HomeCachePort {
    suspend fun cacheHome(sections: List<ResolvedSection>) {}
    suspend fun cacheHome(sections: List<ResolvedSection>, lease: HomeCacheWriteLease) {
        cacheHome(sections)
    }
    suspend fun getCachedHome(): HomeCacheSnapshot? = null
}

/** Network-only default: caches nothing, returns nothing. */
object NoOpHomeCachePort : HomeCachePort
