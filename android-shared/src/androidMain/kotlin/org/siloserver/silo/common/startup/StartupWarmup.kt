package org.siloserver.silo.common.startup

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import org.siloserver.silo.common.ui.components.resolveAvatarUrl
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * How much home artwork a cold-start warmup pulls through the Coil caches, and
 * at what decode sizes. Mirrors StartupContentPrefetcher's per-platform artwork
 * budgets on Apple (28 URLs on tvOS vs 12 on iOS): the TV's first screen paints
 * a full-width first row plus the focus marquee's logo and backdrop, so it
 * needs a deeper warmup than the phone's poster grid.
 */
data class StartupArtworkPlan(
    val maxUrls: Int,
    val posterWidthPx: Int,
    val posterHeightPx: Int,
    /** Progress-row stills — and, when [warmFirstRowHeroArt], the marquee backdrops. */
    val backdropWidthPx: Int,
    val backdropHeightPx: Int,
    val logoWidthPx: Int = 0,
    val logoHeightPx: Int = 0,
    /**
     * TV only: the first content row also warms each card's backdrop (the
     * marquee previews whatever is focused) and the first item's logo, since
     * entry focus lands on that row's first card and the marquee shows it
     * immediately.
     */
    val warmFirstRowHeroArt: Boolean = false,
) {
    companion object {
        /**
         * Hero sizes mirror TvSkylineSectionFeed's preload constants (backdrop
         * 1229×756, logo 880×200); posters are RowDimens.PosterWidth/Height
         * (130×195dp) at the 2× density of a 1080p TV.
         */
        fun tv() = StartupArtworkPlan(
            maxUrls = 28,
            posterWidthPx = 260,
            posterHeightPx = 390,
            backdropWidthPx = 1229,
            backdropHeightPx = 756,
            logoWidthPx = 880,
            logoHeightPx = 200,
            warmFirstRowHeroArt = true,
        )

        /** Phone home renders posters and episode stills only — no hero art. */
        fun phone() = StartupArtworkPlan(
            maxUrls = 12,
            posterWidthPx = 360,
            posterHeightPx = 540,
            backdropWidthPx = 480,
            backdropHeightPx = 270,
        )
    }
}

/**
 * Best-effort authenticated cold-start warmup. This must never decide routing
 * or block splash dismissal; it just fills the same caches/screens read after
 * the navigation graph enters the authenticated app — including the Coil image
 * caches, so the first Home frame paints finished cards instead of thumbhashes.
 */
suspend fun warmAuthenticatedStartup(
    context: Context,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    personalDataRepository: PersonalDataRepository,
    sectionRepository: SectionRepository,
    homeCache: HomeCachePort,
    serverUrl: String?,
    artworkPlan: StartupArtworkPlan,
) {
    supervisorScope {
        listOf(
            async {
                runCatching { authRepository.getCurrentUser() }
                Unit
            },
            async {
                // The root top bar renders the active profile's avatar right
                // after launch — warm the bytes so it doesn't fill in late.
                runCatching {
                    val profile = profileRepository.getActiveProfile()
                    warmAvatarArtwork(context, listOfNotNull(profile), serverUrl)
                }
                Unit
            },
            async {
                runCatching { personalDataRepository.listUserLibraries() }
                Unit
            },
            async {
                runCatching { warmHome(context, sectionRepository, homeCache, artworkPlan) }
                Unit
            },
        ).awaitAll()
    }
}

/**
 * Cold-start warmup for a launch that lands on profile selection: fetch the
 * profile list so the screen's own request rides a warm HTTP path, and pull
 * the avatar images through Coil so the grid paints finished on entry.
 * Mirrors prefetchForInitialRoute(.needsProfile) on Apple.
 */
suspend fun warmProfileSelectionStartup(
    context: Context,
    profileRepository: ProfileRepository,
    serverUrl: String?,
) {
    val profiles = when (val result = runCatching { profileRepository.listProfiles() }.getOrNull()) {
        is ApiResult.Success -> result.data
        else -> return
    }
    warmAvatarArtwork(context, profiles.take(maxProfileArtworkUrls), serverUrl)
}

private suspend fun CoroutineScope.warmHome(
    context: Context,
    sectionRepository: SectionRepository,
    homeCache: HomeCachePort,
    artworkPlan: StartupArtworkPlan,
) {
    when (val result = sectionRepository.getHomeSections()) {
        is ApiResult.Success -> {
            val resolvedPairs: List<Pair<ResolvedSection, Boolean>> =
                result.data.sections.map { section ->
                    async {
                        when (val itemsResult = sectionRepository.getHomeSectionItems(section.id)) {
                            is ApiResult.Success -> (itemsResult.data.section ?: section) to true
                            is ApiResult.Error,
                            is ApiResult.NetworkError -> section to false
                        }
                    }
                }.awaitAll()

            if (resolvedPairs.all { it.second }) {
                val resolved = resolvedPairs.map { it.first }.filter { it.items.isNotEmpty() }
                if (resolved.isNotEmpty()) {
                    homeCache.cacheHome(resolved)
                    warmHomeArtwork(context, resolved, artworkPlan)
                }
            }
        }
        is ApiResult.Error,
        is ApiResult.NetworkError -> Unit
    }
}

/**
 * Warms the artwork the first Home screen paints, in paint order: the first
 * content row's art (plus its first item's logo and per-card backdrops on TV,
 * where the marquee previews the entry-focused card), then the remaining rows'
 * card art until the plan's URL budget runs out. Ports
 * StartupContentPrefetcher.prefetchHomeArtwork's ordering from Apple.
 */
private suspend fun warmHomeArtwork(
    context: Context,
    sections: List<ResolvedSection>,
    plan: StartupArtworkPlan,
) {
    val seen = HashSet<String>()
    val requests = ArrayList<ImageRequest>(plan.maxUrls)

    fun append(urlString: String?, widthPx: Int, heightPx: Int) {
        if (requests.size >= plan.maxUrls) return
        val url = urlString?.trim().orEmpty()
        if (url.isEmpty() || !seen.add(url)) return
        requests.add(
            ImageRequest.Builder(context)
                .data(url)
                .size(widthPx, heightPx)
                .build(),
        )
    }

    val contentSections = sections.filter { it.items.isNotEmpty() }
    contentSections.firstOrNull()?.let { firstRow ->
        val episodeRow = firstRow.rendersEpisodeStills()
        if (plan.warmFirstRowHeroArt) {
            append(firstRow.items.firstOrNull()?.logoUrl, plan.logoWidthPx, plan.logoHeightPx)
        }
        for (item in firstRow.items) {
            if (episodeRow) {
                // Episode stills already render the backdrop, so the card art
                // and the marquee art are one fetch.
                append(item.backdropUrl ?: item.posterUrl, plan.backdropWidthPx, plan.backdropHeightPx)
            } else {
                append(item.posterUrl, plan.posterWidthPx, plan.posterHeightPx)
                if (plan.warmFirstRowHeroArt) {
                    append(item.backdropUrl, plan.backdropWidthPx, plan.backdropHeightPx)
                }
            }
            if (requests.size >= plan.maxUrls) break
        }
    }
    for (section in contentSections.drop(1)) {
        val episodeRow = section.rendersEpisodeStills()
        for (item in section.items) {
            if (episodeRow) {
                append(item.backdropUrl ?: item.posterUrl, plan.backdropWidthPx, plan.backdropHeightPx)
            } else {
                append(item.posterUrl, plan.posterWidthPx, plan.posterHeightPx)
            }
            if (requests.size >= plan.maxUrls) break
        }
        if (requests.size >= plan.maxUrls) break
    }

    warmImages(context, requests)
}

private suspend fun warmAvatarArtwork(
    context: Context,
    profiles: List<Profile>,
    serverUrl: String?,
) {
    val requests = profiles
        .mapNotNull { profile ->
            profile.avatar?.let { resolveAvatarUrl(serverUrl.orEmpty(), it) }
        }
        .distinct()
        .map { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(profileAvatarWarmSizePx, profileAvatarWarmSizePx)
                .build()
        }
    warmImages(context, requests)
}

private suspend fun warmImages(context: Context, requests: List<ImageRequest>) {
    if (requests.isEmpty()) return
    val loader = SingletonImageLoader.get(context)
    supervisorScope {
        requests.map { request ->
            async { runCatching { loader.execute(request) } }
        }.awaitAll()
    }
}

/** Rows whose cards render the episode still — mirrors TvSkylineSectionFeed.isTvProgressRow. */
private fun ResolvedSection.rendersEpisodeStills(): Boolean {
    val type = sectionType.lowercase()
    return type.contains("continue") ||
        type.contains("in_progress") ||
        type.contains("next_up") ||
        type.contains("up_next")
}

private const val maxProfileArtworkUrls = 8

/** DiceBear presets already resolve at 256px; server avatars decode fine at it too. */
private const val profileAvatarWarmSizePx = 256
