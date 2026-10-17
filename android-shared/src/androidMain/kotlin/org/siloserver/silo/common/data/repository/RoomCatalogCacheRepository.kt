package org.siloserver.silo.common.data.repository

import org.siloserver.silo.repository.port.CatalogCacheScope

import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.CatalogCacheEntity
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.ArtworkSizing
import org.siloserver.silo.repository.ArtworkSurface
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import kotlinx.serialization.json.Json

/**
 * Room-backed [CatalogCachePort] (Track B). Stores each catalog read as a JSON
 * blob in the generic `catalog_cache` table, scoped to the active
 * `(serverId, profileId)`. Corrupt/forward-incompatible JSON decodes to null
 * rather than crashing the browse screen. Reads that carry artwork are keyed by
 * the widths their screen draws it at.
 */
class RoomCatalogCacheRepository(
    db: SiloDatabase,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val artwork: ArtworkSizing = ArtworkSizing.None,
) : CatalogCachePort {

    private val dao = db.catalogCacheDao()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun invalidateDetailMetadata(scope: CatalogCacheScope?) {
        val owner = scope ?: run {
            val snapshot = snapshotProvider() ?: return
            val profile = snapshot.profileId ?: return
            CatalogCacheScope(snapshot.serverId, profile)
        }
        dao.deleteDetailMetadata(owner.serverId, owner.profileId)
    }

    override suspend fun cacheLibraries(libraries: List<UserLibrary>) =
        cacheLibraries(libraries, currentWriteLease())

    override suspend fun cacheLibraries(libraries: List<UserLibrary>, lease: CatalogCacheWriteLease) {
        put(KEY_LIBRARIES, json.encodeToString(libraries), lease)
    }

    override suspend fun getCachedLibraries(): List<UserLibrary>? =
        get(KEY_LIBRARIES)?.let { runCatching { json.decodeFromString<List<UserLibrary>>(it) }.getOrNull() }

    override suspend fun cacheDefaultLibraryPage(libraryId: Int, response: CatalogResponse) =
        cacheDefaultLibraryPage(libraryId, response, currentWriteLease())

    override suspend fun cacheDefaultLibraryPage(
        libraryId: Int,
        response: CatalogResponse,
        lease: CatalogCacheWriteLease,
    ) = putArtwork(libraryPrefix(libraryId), ArtworkSurface.BROWSE, json.encodeToString(response), lease)

    override suspend fun getCachedDefaultLibraryPage(libraryId: Int): CatalogResponse? =
        getArtwork(libraryPrefix(libraryId), ArtworkSurface.BROWSE)
            ?.let { runCatching { json.decodeFromString<CatalogResponse>(it) }.getOrNull() }

    override suspend fun cacheLibrarySections(libraryId: Int, sections: List<ResolvedSection>) =
        cacheLibrarySections(libraryId, sections, currentWriteLease())

    override suspend fun cacheLibrarySections(
        libraryId: Int,
        sections: List<ResolvedSection>,
        lease: CatalogCacheWriteLease,
    ) {
        put(librarySectionsKey(libraryId), json.encodeToString(sections), lease)
    }

    override suspend fun getCachedLibrarySections(libraryId: Int): List<ResolvedSection>? =
        get(librarySectionsKey(libraryId))?.let { runCatching { json.decodeFromString<List<ResolvedSection>>(it) }.getOrNull() }

    override suspend fun cacheLibrarySectionsV2(libraryId: Int, sections: List<ResolvedSection>, owner: AuthScopeSnapshot,
        artworkFingerprint: String?) {
        val profile = owner.profileId ?: return
        if (owner != snapshotProvider()) return
        val key = librarySectionsV2Key(libraryId, owner, artworkFingerprint)
        val body = json.encodeToString(sections)
        if (body.encodeToByteArray().size > MAX_CACHE_BYTES) {
            dao.delete(owner.serverId, profile, key)
            return
        }
        // Address only the captured owner's key, even if identity changes during Room's suspension.
        dao.upsert(CatalogCacheEntity(owner.serverId, profile, key, body, now()))
        val legacy = librarySectionsV2LegacyKey(libraryId, owner)
        if (key != legacy) runCatching { dao.delete(owner.serverId, profile, legacy) }
    }

    override suspend fun getCachedLibrarySectionsV2(libraryId: Int, owner: AuthScopeSnapshot): List<ResolvedSection>? {
        val profile = owner.profileId ?: return null
        if (owner != snapshotProvider()) return null
        val key = librarySectionsV2Key(libraryId, owner)
        val legacy = librarySectionsV2LegacyKey(libraryId, owner)
        val row = runCatching {
            dao.get(owner.serverId, profile, key) ?: if (key != legacy) dao.get(owner.serverId, profile, legacy) else null
        }.getOrNull()
        if (owner != snapshotProvider()) return null
        return row?.json?.let { runCatching { json.decodeFromString<List<ResolvedSection>>(it) }.getOrNull() }
    }

    private suspend fun librarySectionsV2Key(libraryId: Int, owner: AuthScopeSnapshot, artworkFingerprint: String? = null): String =
        owner.identityCacheKey(artworkKey("library-sections-v2:$libraryId", ArtworkSurface.HOME, artworkFingerprint))
    private fun librarySectionsV2LegacyKey(libraryId: Int, owner: AuthScopeSnapshot): String =
        owner.identityCacheKey("library-sections-v2:$libraryId")

    override suspend fun cacheItemDetail(contentId: String, detail: ItemDetail) =
        cacheItemDetail(contentId, detail, currentWriteLease())

    override suspend fun cacheItemDetail(
        contentId: String,
        detail: ItemDetail,
        lease: CatalogCacheWriteLease,
    ) = putArtwork(itemDetailPrefix(contentId), ArtworkSurface.DETAIL, json.encodeToString(detail), lease)

    override suspend fun getCachedItemDetail(contentId: String): ItemDetail? =
        getArtwork(itemDetailPrefix(contentId), ArtworkSurface.DETAIL)
            ?.let { runCatching { json.decodeFromString<ItemDetail>(it) }.getOrNull() }

    override suspend fun cacheSeasons(seriesId: String, response: SeasonsResponse) =
        cacheSeasons(seriesId, response, currentWriteLease())

    override suspend fun cacheSeasons(
        seriesId: String,
        response: SeasonsResponse,
        lease: CatalogCacheWriteLease,
    ) {
        put(seasonsKey(seriesId), json.encodeToString(response), lease)
    }

    override suspend fun getCachedSeasons(seriesId: String): SeasonsResponse? =
        get(seasonsKey(seriesId))?.let { runCatching { json.decodeFromString<SeasonsResponse>(it) }.getOrNull() }

    override suspend fun cacheEpisodes(seriesId: String, seasonNumber: Int, response: EpisodesResponse) =
        cacheEpisodes(seriesId, seasonNumber, response, currentWriteLease())

    override suspend fun cacheEpisodes(
        seriesId: String,
        seasonNumber: Int,
        response: EpisodesResponse,
        lease: CatalogCacheWriteLease,
        surface: ArtworkSurface,
    ) = putArtwork(episodesPrefix(seriesId, seasonNumber), surface, json.encodeToString(response), lease)

    override suspend fun getCachedEpisodes(seriesId: String, seasonNumber: Int, surface: ArtworkSurface): EpisodesResponse? =
        getArtwork(episodesPrefix(seriesId, seasonNumber), surface)
            ?.let { runCatching { json.decodeFromString<EpisodesResponse>(it) }.getOrNull() }

    /** Whether the row was written. */
    private suspend fun put(
        cacheKey: String,
        jsonStr: String,
        lease: CatalogCacheWriteLease,
    ): Boolean {
        if (lease.identityGeneration != identityTransitions.generation.value) return false
        val snapshot = snapshotProvider() ?: return false
        val profileId = snapshot.profileId ?: return false
        if (lease.identityGeneration != identityTransitions.generation.value) return false
        // A Room row must fit SQLite's ~2MB CursorWindow or the *read* throws
        // SQLiteBlobTooBigException. Big library pages can exceed it, so don't
        // store an unreadable row — drop any prior row for this key and skip.
        if (jsonStr.encodeToByteArray().size > MAX_CACHE_BYTES) {
            runCatching { dao.delete(snapshot.serverId, profileId, cacheKey) }
            return false
        }
        dao.upsert(
            CatalogCacheEntity(
                serverId = snapshot.serverId,
                profileId = profileId,
                cacheKey = cacheKey,
                json = jsonStr,
                cachedAtMs = now(),
            ),
        )
        return true
    }

    private fun currentWriteLease() =
        CatalogCacheWriteLease(identityTransitions.generation.value)

    private suspend fun get(cacheKey: String): String? {
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        // An oversized legacy row throws SQLiteBlobTooBigException on read; purge
        // it so it can't keep crashing the browse screen, and serve online.
        return runCatching { dao.get(snapshot.serverId, profileId, cacheKey) }
            .getOrElse {
                runCatching { dao.delete(snapshot.serverId, profileId, cacheKey) }
                null
            }?.json
    }

    // A write uses the fingerprint its read captured; a read uses the current one.
    private suspend fun artworkKey(prefix: String, surface: ArtworkSurface, captured: String?) =
        if (captured != null) ArtworkSizing.cacheKey(prefix, captured) else artwork.cacheKey(prefix, surface)

    // Rows cached before artwork widths were keyed by the bare prefix. A read
    // falls back to one until this item is cached again, which retires it.
    private suspend fun getArtwork(prefix: String, surface: ArtworkSurface): String? {
        val key = artwork.cacheKey(prefix, surface)
        return get(key) ?: if (key != prefix) get(prefix) else null
    }

    private suspend fun putArtwork(prefix: String, surface: ArtworkSurface, jsonStr: String, lease: CatalogCacheWriteLease) {
        val key = artworkKey(prefix, surface, lease.artworkFingerprint)
        if (!put(key, jsonStr, lease) || key == prefix) return
        val snapshot = snapshotProvider() ?: return
        val profileId = snapshot.profileId ?: return
        runCatching { dao.delete(snapshot.serverId, profileId, prefix) }
    }

    private fun libraryPrefix(libraryId: Int) = "library:$libraryId"
    private fun itemDetailPrefix(contentId: String) = "item-detail:$contentId"
    private fun episodesPrefix(seriesId: String, seasonNumber: Int) = "episodes:$seriesId:$seasonNumber"

    private companion object {
        // Stay well under SQLite's ~2MB CursorWindow.
        const val MAX_CACHE_BYTES = 1_500_000
        const val KEY_LIBRARIES = "libraries"
        fun librarySectionsKey(libraryId: Int) = "library-sections:$libraryId"
        fun seasonsKey(seriesId: String) = "seasons:$seriesId"
    }
}
