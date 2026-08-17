package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.siloserver.silo.android.ui.components.SiloTopBar

@Composable
fun PersonalListsScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Favorites", "Watchlist")

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Favorites & Watchlist",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(label) },
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> {
                    val controls = rememberPersonalListControls(PersonalListSource.Favorites)
                    val query by controls.queryState()
                    FavoritesGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        query = query,
                        header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
                    )
                }
                else -> {
                    val controls = rememberPersonalListControls(PersonalListSource.Watchlist)
                    val query by controls.queryState()
                    WatchlistGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        query = query,
                        header = { state -> PersonalListControlsRow(controls = controls, total = state.total) },
                    )
                }
            }
        }
    }
}
