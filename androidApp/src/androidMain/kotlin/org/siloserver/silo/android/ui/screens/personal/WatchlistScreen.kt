package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.siloserver.silo.android.ui.components.SiloTopBar

/**
 * Screen displaying the user's watchlist items in a grid layout.
 *
 * Same pattern as FavoritesScreen: pull-to-refresh, infinite scroll, tap to detail.
 */
@Composable
fun WatchlistScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Watchlist",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val controls = rememberPersonalListControls(PersonalListSource.Watchlist)
        val query by controls.queryState()
        WatchlistGridContent(
            onItemClick = onItemClick,
            contentPadding = padding,
            query = query,
            header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
        )
    }
}
