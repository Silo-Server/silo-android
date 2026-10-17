package org.siloserver.silo.common.data.repository

import androidx.room.withTransaction
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.HomeCacheEntity
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.ArtworkSizing
import org.siloserver.silo.repository.ArtworkSurface
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.repository.port.HomeCacheSnapshot
import org.siloserver.silo.repository.port.HomeCacheWriteLease
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Room-backed [HomeCachePort] (Track B). Stores the resolved home layout as a
 * single JSON blob per `(serverId, profileId)` so the home renders offline.
 *
 * Scope comes from the active [AuthScopeSnapshot]; with no active server/profile
 * there's nothing to cache or serve (returns null). Corrupt/forward-incompatible
 * JSON decodes to null rather than crashing the home screen.
 *
 * The blob is encoded and decoded on [jsonDispatcher]: callers read the cache
 * from the main thread, and with 100-item Home rows the blob is about 1 MB,
 * which takes hundreds of milliseconds to decode on a low-end TV.
 *
 * The v2 key carries the artwork widths Home is drawn at, so a layout change
 * never serves artwork sized for another.
 */
class RoomHomeCacheRepository(
    private val db: SiloDatabase,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val jsonDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val artwork: ArtworkSizing = ArtworkSizing.None,
) : HomeCachePort {

    private val dao = db.homeCacheDao()
    private val scopedDao = db.catalogCacheDao()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun encode(sections: List<ResolvedSection>): String =
        withContext(jsonDispatcher) { json.encodeToString(sections) }

    private suspend fun decode(body: String): List<ResolvedSection>? = withContext(jsonDispatcher) {
        // Corrupt or forward-incompatible JSON (a SerializationException, which is an
        // IllegalArgumentException) is a cache miss; cancellation still propagates.
        try {
            json.decodeFromString<List<ResolvedSection>>(body)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun artworkFingerprint(): String = artwork.cacheFingerprint(ArtworkSurface.HOME)

    override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?,
        stillCurrent: () -> Boolean) {
        val profile = owner.profileId ?: return
        // Encoding suspends, so it runs before the ownership check, not between it and the write.
        val body = encode(sections)
        if (owner != snapshotProvider()) return
        val key = scopedKey(owner, artworkFingerprint)
        db.withTransaction {
            if (!stillCurrent()) return@withTransaction
            if (body.encodeToByteArray().size > MAX_CACHE_BYTES) scopedDao.delete(owner.serverId, profile, key)
            else scopedDao.upsert(org.siloserver.silo.common.data.db.entity.CatalogCacheEntity(owner.serverId, profile, key, body, now()))
        }
    }

    override suspend fun cacheHomeV2IfAbsent(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?,
        stillCurrent: () -> Boolean) {
        val profile = owner.profileId ?: return
        val body = encode(sections)
        if (body.encodeToByteArray().size > MAX_CACHE_BYTES) return
        if (owner != snapshotProvider()) return
        val key = scopedKey(owner, artworkFingerprint)
        db.withTransaction {
            if (!stillCurrent() || scopedDao.get(owner.serverId, profile, key) != null) return@withTransaction
            scopedDao.upsert(org.siloserver.silo.common.data.db.entity.CatalogCacheEntity(owner.serverId, profile, key, body, now()))
        }
    }

    override suspend fun getCachedHomeV2(owner: AuthScopeSnapshot): HomeCacheSnapshot? {
        val profile = owner.profileId ?: return null
        if (owner != snapshotProvider()) return null
        val row = runCatching { scopedDao.get(owner.serverId, profile, scopedKey(owner)) }.getOrNull() ?: return null
        val sections = decode(row.json)
        if (owner != snapshotProvider()) return null
        return sections?.let { HomeCacheSnapshot(it, row.cachedAtMs) }
    }

    // A write uses the fingerprint its read captured; a read uses the current one.
    private fun scopedKey(owner: AuthScopeSnapshot, captured: String? = null): String =
        owner.identityCacheKey(ArtworkSizing.cacheKey("home-sections-v2", captured ?: artworkFingerprint()))

    override suspend fun cacheHome(sections: List<ResolvedSection>) {
        cacheHome(
            sections = sections,
            lease = HomeCacheWriteLease(identityTransitions.generation.value),
        )
    }

    override suspend fun cacheHome(
        sections: List<ResolvedSection>,
        lease: HomeCacheWriteLease,
    ) {
        if (lease.identityGeneration != identityTransitions.generation.value) return
        val sectionsJson = encode(sections)
        val snapshot = snapshotProvider() ?: return
        val profileId = snapshot.profileId ?: return
        if (lease.identityGeneration != identityTransitions.generation.value) return
        // A Room row must fit SQLite's ~2MB CursorWindow or the *read* throws
        // SQLiteBlobTooBigException. Large reorganized home layouts can exceed
        // that, so don't cache an unreadable row — drop any prior row (which may
        // itself be an oversized poison) and just serve the home online.
        if (sectionsJson.encodeToByteArray().size > MAX_CACHE_BYTES) {
            runCatching { dao.delete(snapshot.serverId, profileId) }
            return
        }
        dao.upsert(
            HomeCacheEntity(
                serverId = snapshot.serverId,
                profileId = profileId,
                sectionsJson = sectionsJson,
                cachedAtMs = now(),
            ),
        )
    }

    override suspend fun getCachedHome(): HomeCacheSnapshot? {
        val identityGeneration = identityTransitions.generation.value
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        // An oversized legacy row (written before the cap) throws
        // SQLiteBlobTooBigException on read; purge it so it can't crash the home
        // screen again, and fall through to an online load.
        val row = runCatching { dao.get(snapshot.serverId, profileId) }
            .getOrElse {
                runCatching { dao.delete(snapshot.serverId, profileId) }
                null
            } ?: return null
        val sections = decode(row.sectionsJson) ?: return null
        // The read and the decode suspend: serve nothing if the identity moved meanwhile.
        if (snapshot != snapshotProvider() || identityGeneration != identityTransitions.generation.value) return null
        return HomeCacheSnapshot(sections = sections, cachedAtMs = row.cachedAtMs)
    }

    private companion object {
        // Stay well under SQLite's ~2MB CursorWindow (row must include other
        // columns + overhead), so a cached row is always readable.
        const val MAX_CACHE_BYTES = 1_500_000
    }
}
