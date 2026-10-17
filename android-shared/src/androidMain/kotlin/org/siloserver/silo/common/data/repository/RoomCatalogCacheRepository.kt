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

    override suspend fun cacheLibraries(libraries: List<UserLibrary>, lease: CatalogCacheWriteLease) =
        put(KEY_LIBRARIES, json.encodeToString(libraries), lease)

    override suspend fun getCachedLibraries(): List<UserLibrary>? =
        get(KEY_LIBRARIES)?.let { runCatching { json.decodeFromString<List<UserLibrary>>(it) }.getOrNull() }

    override suspend fun cacheDefaultLibraryPage(libraryId: Int, response: CatalogResponse) =
        cacheDefaultLibraryPage(libraryId, response, currentWriteLease())

    override suspend fun cacheDefaultLibraryPage(
        libraryId: Int,
        response: CatalogResponse,
        lease: CatalogCacheWriteLease,
    ) = put(libraryKey(libraryId, lease.artworkFingerprint), json.encodeToString(response), lease)

    override suspend fun getCachedDefaultLibraryPage(libraryId: Int): CatalogResponse? =
        get(libraryKey(libraryId))?.let { runCatching { json.decodeFromString<CatalogResponse>(it) }.getOrNull() }

    override suspend fun cacheLibrarySections(libraryId: Int, sections: List<ResolvedSection>) =
        cacheLibrarySections(libraryId, sections, currentWriteLease())

    override suspend fun cacheLibrarySections(
        libraryId: Int,
        sections: List<ResolvedSection>,
        lease: CatalogCacheWriteLease,
    ) = put(librarySectionsKey(libraryId), json.encodeToString(sections), lease)

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
    }

    override suspend fun getCachedLibrarySectionsV2(libraryId: Int, owner: AuthScopeSnapshot): List<ResolvedSection>? {
        val profile = owner.profileId ?: return null
        if (owner != snapshotProvider()) return null
        val key = librarySectionsV2Key(libraryId, owner)
        val row = runCatching { dao.get(owner.serverId, profile, key) }.getOrNull()
        if (owner != snapshotProvider()) return null
        return row?.json?.let { runCatching { json.decodeFromString<List<ResolvedSection>>(it) }.getOrNull() }
    }

    private fun librarySectionsV2Key(libraryId: Int, owner: AuthScopeSnapshot, artworkFingerprint: String? = null): String =
        owner.identityCacheKey(artworkKey("library-sections-v2:$libraryId", ArtworkSurface.HOME, artworkFingerprint))

    override suspend fun cacheItemDetail(contentId: String, detail: ItemDetail) =
        cacheItemDetail(contentId, detail, currentWriteLease())

    override suspend fun cacheItemDetail(
        contentId: String,
        detail: ItemDetail,
        lease: CatalogCacheWriteLease,
    ) = put(itemDetailKey(contentId, lease.artworkFingerprint), json.encodeToString(detail), lease)

    override suspend fun getCachedItemDetail(contentId: String): ItemDetail? =
        get(itemDetailKey(contentId))?.let { runCatching { json.decodeFromString<ItemDetail>(it) }.getOrNull() }

    override suspend fun cacheSeasons(seriesId: String, response: SeasonsResponse) =
        cacheSeasons(seriesId, response, currentWriteLease())

    override suspend fun cacheSeasons(
        seriesId: String,
        response: SeasonsResponse,
        lease: CatalogCacheWriteLease,
    ) = put(seasonsKey(seriesId), json.encodeToString(response), lease)

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
    ) = put(episodesKey(seriesId, seasonNumber, surface, lease.artworkFingerprint), json.encodeToString(response), lease)

    override suspend fun getCachedEpisodes(seriesId: String, seasonNumber: Int, surface: ArtworkSurface): EpisodesResponse? =
        get(episodesKey(seriesId, seasonNumber, surface))?.let { runCatching { json.decodeFromString<EpisodesResponse>(it) }.getOrNull() }

    private suspend fun put(
        cacheKey: String,
        jsonStr: String,
        lease: CatalogCacheWriteLease,
    ) {
        if (lease.identityGeneration != identityTransitions.generation.value) return
        val snapshot = snapshotProvider() ?: return
        val profileId = snapshot.profileId ?: return
        if (lease.identityGeneration != identityTransitions.generation.value) return
        // A Room row must fit SQLite's ~2MB CursorWindow or the *read* throws
        // SQLiteBlobTooBigException. Big library pages can exceed it, so don't
        // store an unreadable row — drop any prior row for this key and skip.
        if (jsonStr.encodeToByteArray().size > MAX_CACHE_BYTES) {
            runCatching { dao.delete(snapshot.serverId, profileId, cacheKey) }
            return
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
    private fun artworkKey(prefix: String, surface: ArtworkSurface, captured: String?) =
        if (captured != null) ArtworkSizing.cacheKey(prefix, captured) else artwork.cacheKey(prefix, surface)
    private fun libraryKey(libraryId: Int, captured: String? = null) =
        artworkKey("library:$libraryId", ArtworkSurface.BROWSE, captured)
    private fun itemDetailKey(contentId: String, captured: String? = null) =
        artworkKey("item-detail:$contentId", ArtworkSurface.DETAIL, captured)
    private fun episodesKey(seriesId: String, seasonNumber: Int, surface: ArtworkSurface, captured: String? = null) =
        artworkKey("episodes:$seriesId:$seasonNumber", surface, captured)

    private companion object {
        // Stay well under SQLite's ~2MB CursorWindow.
        const val MAX_CACHE_BYTES = 1_500_000
        const val KEY_LIBRARIES = "libraries"
        fun librarySectionsKey(libraryId: Int) = "library-sections:$libraryId"
        fun seasonsKey(seriesId: String) = "seasons:$seriesId"
    }
}
