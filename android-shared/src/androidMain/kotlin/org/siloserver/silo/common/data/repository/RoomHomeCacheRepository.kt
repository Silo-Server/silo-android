package org.siloserver.silo.common.data.repository

import androidx.room.withTransaction
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.HomeCacheEntity
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.repository.port.HomeCacheSnapshot
import org.siloserver.silo.repository.port.HomeCacheWriteLease
import kotlinx.serialization.json.Json

/**
 * Room-backed [HomeCachePort] (Track B). Stores the resolved home layout as a
 * single JSON blob per `(serverId, profileId)` so the home renders offline.
 *
 * Scope comes from the active [AuthScopeSnapshot]; with no active server/profile
 * there's nothing to cache or serve (returns null). Corrupt/forward-incompatible
 * JSON decodes to null rather than crashing the home screen.
 */
class RoomHomeCacheRepository(
    private val db: SiloDatabase,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : HomeCachePort {

    private val dao = db.homeCacheDao()
    private val scopedDao = db.catalogCacheDao()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, stillCurrent: () -> Boolean) {
        val profile = owner.profileId ?: return
        if (owner != snapshotProvider()) return
        val key = scopedKey(owner)
        val body = json.encodeToString(sections)
        db.withTransaction {
            if (!stillCurrent()) return@withTransaction
            if (body.encodeToByteArray().size > MAX_CACHE_BYTES) scopedDao.delete(owner.serverId, profile, key)
            else scopedDao.upsert(org.siloserver.silo.common.data.db.entity.CatalogCacheEntity(owner.serverId, profile, key, body, now()))
        }
    }

    override suspend fun cacheHomeV2IfAbsent(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, stillCurrent: () -> Boolean) {
        val profile = owner.profileId ?: return
        if (owner != snapshotProvider()) return
        val key = scopedKey(owner)
        val body = json.encodeToString(sections)
        if (body.encodeToByteArray().size > MAX_CACHE_BYTES) return
        db.withTransaction {
            if (!stillCurrent() || scopedDao.get(owner.serverId, profile, key) != null) return@withTransaction
            scopedDao.upsert(org.siloserver.silo.common.data.db.entity.CatalogCacheEntity(owner.serverId, profile, key, body, now()))
        }
    }

    override suspend fun getCachedHomeV2(owner: AuthScopeSnapshot): HomeCacheSnapshot? {
        val profile = owner.profileId ?: return null
        if (owner != snapshotProvider()) return null
        val row = runCatching { scopedDao.get(owner.serverId, profile, scopedKey(owner)) }.getOrNull()
        if (owner != snapshotProvider()) return null
        return row?.let { runCatching { HomeCacheSnapshot(json.decodeFromString<List<ResolvedSection>>(it.json), it.cachedAtMs) }.getOrNull() }
    }

    private fun scopedKey(owner: AuthScopeSnapshot): String = owner.identityCacheKey("home-sections-v2")

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
        val snapshot = snapshotProvider() ?: return
        val profileId = snapshot.profileId ?: return
        if (lease.identityGeneration != identityTransitions.generation.value) return
        val sectionsJson = json.encodeToString(sections)
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
        val sections = runCatching {
            json.decodeFromString<List<ResolvedSection>>(row.sectionsJson)
        }.getOrNull() ?: return null
        return HomeCacheSnapshot(sections = sections, cachedAtMs = row.cachedAtMs)
    }

    private companion object {
        // Stay well under SQLite's ~2MB CursorWindow (row must include other
        // columns + overhead), so a cached row is always readable.
        const val MAX_CACHE_BYTES = 1_500_000
    }
}
