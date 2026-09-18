package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.section.LibraryCollection
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionRouteTest {
    private val appNavigationSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt",
    ).readText()

    @Test
    fun regularLibraryCollectionCarriesItsSource() {
        assertEquals(
            "collection/regular?libraryId=7&source=library_collection",
            libraryCollectionDetailRoute(
                LibraryCollection(id = "regular", name = "Regular", kind = "regular"),
                libraryId = 7,
            ),
        )
    }

    @Test
    fun userLibraryCollectionCarriesItsSource() {
        assertEquals(
            "collection/user?libraryId=7&source=user_collection",
            libraryCollectionDetailRoute(
                LibraryCollection(id = "user", name = "User", kind = "user_collections"),
                libraryId = 7,
            ),
        )
    }

    @Test
    fun legacyLibraryCollectionRouteRemainsSupported() {
        assertEquals(
            "collection/legacy?libraryId=7",
            Route.CollectionDetail(collectionId = "legacy", libraryId = 7).route,
        )
    }

    @Test
    fun personalCollectionRouteRemainsUnscoped() {
        assertEquals(
            "collection/personal",
            Route.CollectionDetail(collectionId = "personal").route,
        )
    }

    @Test
    fun collectionDestinationRegistersTheSourceArgument() {
        val collectionDestination = appNavigationSource.substringAfter(
            "route = Route.CollectionDetail.ROUTE",
        ).substringBefore(
            "composable(Route.Watchlist.route)",
        )

        assertEquals(1, Regex("navArgument\\(\"source\"\\)").findAll(collectionDestination).count())
    }
}
