package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.R as CommonR

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
                title = stringResource(CommonR.string.personal_favorites_title),
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val controls = rememberPersonalListControls(PersonalListSource.Favorites)
        val query = controls.currentQuery()
        FavoritesGridContent(
            onItemClick = onItemClick,
            contentPadding = padding,
            query = query,
            header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
        )
    }
}
