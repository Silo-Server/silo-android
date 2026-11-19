package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.R as CommonR

@Composable
fun TvLoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvErrorScreen(
    message: String,
    onRetry: (() -> Unit)? = null,
    // Optional secondary action, e.g. the "Try Anyway" escape hatch shown when
    // the server is unreachable (issue #33).
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(CommonR.string.common_retry), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Button(
                    onClick = onSecondaryAction,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                ) {
                    Text(secondaryActionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
