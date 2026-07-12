package org.siloserver.silo.tv.ui.screens.people

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPersonDetailSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/people/TvPersonDetailScreen.kt",
    ).readText()

    private val mediaCardSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvMediaCard.kt",
    ).readText()

    private val gridSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvCatalogGrid.kt",
    ).readText()

    private val castSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvCastCrewSection.kt",
    ).readText()

    @Test
    fun usesSharedPresentationPaginationAndAudiobookArtworkRules() {
        assertTrue(source.contains("personMetadataBadges("))
        assertTrue(source.contains("tvPersonFilmographyCountLabel("))
        assertTrue(source.contains("availableFilters = state.availableFilters"))
        assertTrue(source.contains("onLoadMore = viewModel::loadMoreIfNeeded"))
        assertTrue(source.contains("hasMore = state.hasMore"))
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains("val width = 150.dp"))
        // tvOS person identity header: large name, portrait-to-copy gap, and
        // a clamped biography that opens into the full scrollable treatment.
        assertTrue(source.contains("fontSize = 36.sp"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.spacedBy(24.dp)"))
        assertTrue(source.contains("maxLines = 7"))
        // QA 2026-07-08: 6 columns to match every other catalog grid.
        assertTrue(source.contains("private const val PersonGridColumns = 6"))
        assertTrue(source.contains("private val PersonGridItemSpacing = 20.dp"))
        assertTrue(source.contains("No biography or personal details are available yet."))
        assertTrue(source.contains("artworkAspectRatioForItem = ::personWorkCardAspectRatio"))
        assertTrue(source.contains("private fun personWorkCardAspectRatio(item: BrowseItem): Float?"))
        assertTrue(source.contains("item.type == \"audiobook\""))
        assertTrue(mediaCardSource.contains("artworkAspectRatio: Float? = null"))
        assertTrue(mediaCardSource.contains("effectiveAspectRatio"))
        assertTrue(gridSource.contains("artworkAspectRatioForItem: (BrowseItem) -> Float? = { item ->"))
        assertTrue(gridSource.contains("artworkAspectRatio = artworkAspectRatioForItem(item)"))
        assertTrue(source.contains("header = {"))
        assertFalse(source.contains("TvPersonMediaFilter.entries.forEach"))
        assertFalse(source.contains("person.birthDate?.takeIf"))
        assertFalse(castSource.contains("no-op"))
        assertFalse(castSource.contains("person detail isn't wired"))
    }
}
