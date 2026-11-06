package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.R as CommonR

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
                title = stringResource(CommonR.string.personal_watchlist_title),
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val controls = rememberPersonalListControls(PersonalListSource.Watchlist)
        val query = controls.currentQuery()
        WatchlistGridContent(
            onItemClick = onItemClick,
            contentPadding = padding,
            query = query,
            header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
        )
    }
}
