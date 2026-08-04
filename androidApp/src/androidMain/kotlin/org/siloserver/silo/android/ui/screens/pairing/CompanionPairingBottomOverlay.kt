package org.siloserver.silo.android.ui.screens.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.common.pairing.CompanionPairingApproval
import org.siloserver.silo.common.pairing.CompanionPairingServer
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget

/** Bottom-anchored companion setup card matching the iOS presentation. */
@Composable
fun CompanionPairingBottomOverlay(
    target: CompanionPairingTarget?,
    status: CompanionPairingStatus,
    approval: CompanionPairingApproval?,
    serverChoices: List<CompanionPairingServer>?,
    onPair: (CompanionPairingTarget) -> Unit,
    onServersSelected: (Set<String>) -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var retainedTarget by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target != null) retainedTarget = target
    }
    val visible = target != null
    val isIdle = status is CompanionPairingStatus.Idle

    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(isIdle) {
                        detectTapGestures { if (isIdle) onDismiss() }
                    },
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it },
            ) + fadeIn(tween(180)),
            exit = slideOutVertically(
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMedium,
                ),
                targetOffsetY = { it },
            ) + fadeOut(tween(140)),
        ) {
            retainedTarget?.let { rememberedTarget ->
                PairingCard(
                    target = rememberedTarget,
                    status = status,
                    approval = approval,
                    serverChoices = serverChoices,
                    onPair = { onPair(rememberedTarget) },
                    onServersSelected = onServersSelected,
                    onApprove = onApprove,
                    onDecline = onDecline,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    target: CompanionPairingTarget,
    status: CompanionPairingStatus,
    approval: CompanionPairingApproval?,
    serverChoices: List<CompanionPairingServer>?,
    onPair: () -> Unit,
    onServersSelected: (Set<String>) -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomInset = maxOf(
        LocalBottomChromeInset.current,
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

    Card(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .padding(
                start = 10.dp,
                top = 8.dp,
                end = 10.dp,
                bottom = bottomInset + 8.dp,
            )
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .animateContentSize()
            .semantics { paneTitle = "TV setup" },
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 22.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 18.dp)
                    .width(38.dp)
                    .height(5.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        RoundedCornerShape(50),
                    ),
            )

            when {
                approval != null -> MatchConfirmation(
                    approval = approval,
                    onApprove = onApprove,
                    onDecline = onDecline,
                )
                serverChoices != null -> ServerPicker(
                    targetName = target.name,
                    servers = serverChoices,
                    onContinue = onServersSelected,
                    onCancel = onDismiss,
                )
                status is CompanionPairingStatus.Idle -> DiscoveryStep(
                    target = target,
                    onPair = onPair,
                    onDismiss = onDismiss,
                )
                status is CompanionPairingStatus.Completed -> TerminalStep(
                    successful = true,
                    title = status.serverNames.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "Set up ")
                        ?: "TV setup complete",
                    message = "${status.targetName} is ready.",
                    primaryLabel = "Done",
                    onPrimary = onDismiss,
                )
                status is CompanionPairingStatus.Failed -> TerminalStep(
                    successful = false,
                    title = "TV setup needs attention",
                    message = status.message,
                    primaryLabel = "Try Again",
                    onPrimary = onPair,
                    onSecondary = onDismiss,
                )
                else -> ProgressStep(status = status, onCancel = onDismiss)
            }
        }
    }
}

@Composable
private fun ServerPicker(
    targetName: String,
    servers: List<CompanionPairingServer>,
    onContinue: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedIds by remember(servers) { mutableStateOf(emptySet<String>()) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tv,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Choose servers", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Sign $targetName in to…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        servers.forEach { server ->
            val selected = server.id in selectedIds
            Surface(
                onClick = {
                    selectedIds = if (selected) selectedIds - server.id else selectedIds + server.id
                },
                shape = RoundedCornerShape(14.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Checkbox(checked = selected, onCheckedChange = null)
                }
            }
        }

        Button(
            onClick = { onContinue(selectedIds) },
            enabled = selectedIds.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 6.dp),
        ) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun DiscoveryStep(
    target: CompanionPairingTarget,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Tv,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = "Set Up ${target.name}",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Sign ${target.name} in to your servers from this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(label = "Set Up", onClick = onPair, modifier = Modifier.padding(top = 14.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Not Now")
        }
    }
}

@Composable
private fun MatchConfirmation(
    approval: CompanionPairingApproval,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Make sure ${approval.targetName} shows",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = approval.serverMatchCode.uppercase(),
            style = MaterialTheme.typography.headlineLarge,
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "for ${approval.serverName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryAction(
            label = "Yes, this matches",
            onClick = onApprove,
            modifier = Modifier.padding(top = 14.dp),
        )
        TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
            Text("Doesn't match")
        }
    }
}

@Composable
private fun ProgressStep(status: CompanionPairingStatus, onCancel: () -> Unit) {
    val (title, message) = when (status) {
        is CompanionPairingStatus.Connecting -> "Connecting…" to status.targetName
        is CompanionPairingStatus.PickServers -> "Connecting…" to status.targetName
        is CompanionPairingStatus.PushingServer ->
            "Setting up…" to "Continue on ${status.targetName} — allow this phone to set it up."
        is CompanionPairingStatus.AwaitingMatchConfirmation ->
            "Setting up…" to "Waiting for the match code."
        is CompanionPairingStatus.Approving ->
            "Setting up…" to "Approving ${status.serverName} on ${status.targetName}."
        is CompanionPairingStatus.SignedIn ->
            "Setting up…" to "${status.serverName} signed in."
        else -> "Setting up…" to "Please wait."
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        CircularProgressIndicator(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(32.dp),
            strokeWidth = 3.dp,
        )
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun TerminalStep(
    successful: Boolean,
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (successful) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            contentDescription = null,
            tint = if (successful) Color(0xFF34C759) else Color(0xFFFFC107),
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(label = primaryLabel, onClick = onPrimary, modifier = Modifier.padding(top = 8.dp))
        onSecondary?.let { secondary ->
            TextButton(onClick = secondary, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
