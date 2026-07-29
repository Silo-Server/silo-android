package org.siloserver.silo.android.ui.screens.watchtogether

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.siloserver.silo.watchtogether.canDismissRoomEntry
import org.koin.compose.viewmodel.koinViewModel

/**
 * Watch Together entry sheet, opened from the item-detail overflow.
 * Host = create a room with this title pre-selected. Join = enter an
 * invite code. On success [onNavigate] fires with the resolved route
 * (synced player or lobby). Styled after the other detail bottom sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchTogetherEntrySheet(
    contentId: String,
    fileId: Int?,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchTogetherEntryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var code by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    val latestBusy by rememberUpdatedState(state.busy)

    LaunchedEffect(state.destination) {
        val dest = state.destination ?: return@LaunchedEffect
        viewModel.consumeDestination()
        onDismiss()
        onNavigate(dest)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (canDismissRoomEntry(state.busy)) onDismiss()
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                target != SheetValue.Hidden || canDismissRoomEntry(latestBusy)
            },
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "Watch Together",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (!showJoin) {
                Button(
                    onClick = { viewModel.host(contentId, fileId) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.busy) "Creating…" else "Host a room") }

                OutlinedButton(
                    onClick = { viewModel.hostEmptyVoteRoom() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Host a vote room") }

                OutlinedButton(
                    onClick = { viewModel.clearError(); showJoin = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join by code") }
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(8) },
                    label = { Text("Invite code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.joinByCode(code) },
                    enabled = !state.busy && code.length >= 4,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    else Text("Join")
                }
                OutlinedButton(
                    onClick = { viewModel.clearError(); showJoin = false },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
