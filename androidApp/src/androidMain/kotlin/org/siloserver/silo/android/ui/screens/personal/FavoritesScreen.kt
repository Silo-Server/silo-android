package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.siloserver.silo.android.ui.components.SiloTopBar

/**
 * Screen displaying the user's favorite items in a grid layout.
 *
 * Features:
 * - Pull-to-refresh
 * - Infinite scroll pagination
 * - Tap to navigate to item detail
 * - Watched badge overlay on played items
 */
@Composable
fun FavoritesScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Favorites",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val controls = rememberPersonalListControls(PersonalListSource.Favorites)
        val query by controls.queryState()
        FavoritesGridContent(
            onItemClick = onItemClick,
            contentPadding = padding,
            query = query,
            header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
        )
    }
}
