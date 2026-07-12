package org.siloserver.silo.tv.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvPersonNavigationSourceTest {
    private val shellSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()

    private val searchSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchScreen.kt",
    ).readText()

    private val gridSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvCatalogGrid.kt",
    ).readText()

    private val appNavigationSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt",
    ).readText()

    private val detailViewModelSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailViewModel.kt",
    ).readText()

    private val detailScreenSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun searchPersonResultsRouteToPersonDetailInsteadOfItemDetail() {
        assertTrue(
            shellSource.contains("onOpenPersonDetail: (personId: Long) -> Unit"),
            "The TV shell needs a person-detail callback in addition to item detail.",
        )
        assertTrue(
            shellSource.contains("openBrowseItem(") &&
                shellSource.contains("item.type.equals(\"person\", ignoreCase = true)") &&
                shellSource.contains("item.contentId.toLongOrNull()?.let(onOpenPersonDetail)"),
            "Browse/search result clicks should route person items by type.",
        )
        assertTrue(
            searchSource.contains("onResultClick: (BrowseItem) -> Unit") &&
                searchSource.contains("onBrowseItemClick = onResultClick"),
            "Search must preserve the full BrowseItem so navigation can inspect its type.",
        )
        assertTrue(
            gridSource.contains("onBrowseItemClick: ((BrowseItem) -> Unit)? = null") &&
                gridSource.contains("onBrowseItemClick?.invoke(item) ?: onItemClick(item.contentId)"),
            "Catalog grid should support type-aware clicks without breaking existing callers.",
        )
        assertTrue(
            appNavigationSource.contains("onOpenPersonDetail = { personId ->") &&
                appNavigationSource.contains("navController.navigate(TvRoute.PersonDetail(personId).route)"),
            "The top-level TV nav graph should wire shell person clicks to the immersive person detail route.",
        )
        assertTrue(
            detailViewModelSource.contains("fun openPerson(member: CastMember, onOpenPerson: (Long) -> Unit)") &&
                detailViewModelSource.contains("member.personId?.trim()?.toLongOrNull()?.let(onOpenPerson)") &&
                detailViewModelSource.contains("catalogRepository.searchPeople(member.name)") &&
                detailScreenSource.contains("viewModel.openPerson(member) { personId ->") &&
                detailScreenSource.contains("onOpenPerson(personId)") &&
                !detailScreenSource.contains("member.personId?.toIntOrNull()?.let(onOpenPerson)"),
            "Cast-card selection should resolve people by name when person_id is absent or non-numeric instead of no-oping.",
        )
    }
}
