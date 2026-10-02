package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Reusable top app bar for Silo screens.
 *
 * @param title Screen title displayed in the top bar.
 * @param onBackClick When non-null, a back arrow is shown as the navigation icon.
 * @param actions Composable slot for trailing action icons.
 * @param containerColor Bar background. Defaults to the app's `surface`; the
 *   grouped-settings screens pass their own lifted page ground so the bar and
 *   the list below it are one continuous surface instead of two tones.
 * @param centerTitle Centres the title, as Apple's inline navigation titles
 *   are. Settings sub-pages use it to match the Apple apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiloTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    centerTitle: Boolean = false,
) {
    val titleSlot: @Composable () -> Unit = {
        Text(
            text = title,
            style = if (centerTitle) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = if (centerTitle) FontWeight.SemiBold else null,
        )
    }
    val navigationSlot: @Composable () -> Unit = {
        if (onBackClick != null) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate back",
                )
            }
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = containerColor,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (centerTitle) {
        CenterAlignedTopAppBar(
            title = titleSlot,
            navigationIcon = navigationSlot,
            actions = actions,
            colors = colors,
        )
    } else {
        TopAppBar(
            title = titleSlot,
            navigationIcon = navigationSlot,
            actions = actions,
            colors = colors,
        )
    }
}
