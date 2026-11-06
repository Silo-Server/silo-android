package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.R as CommonR

/**
 * Screen displaying the user's watch history in a poster grid layout,
 * matching the Favorites and Watchlist grids exactly.
 */
@Composable
fun HistoryScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = stringResource(CommonR.string.personal_history_title),
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        HistoryGridContent(
            onItemClick = onItemClick,
            contentPadding = padding,
        )
    }
}
