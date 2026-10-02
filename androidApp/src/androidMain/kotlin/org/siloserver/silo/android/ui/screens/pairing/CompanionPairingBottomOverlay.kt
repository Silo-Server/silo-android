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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import org.siloserver.silo.android.R
import org.siloserver.silo.model.auth.DeviceCodeFormat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.screens.auth.tvSignInLine
import org.siloserver.silo.common.pairing.CompanionPairingApproval
import org.siloserver.silo.common.pairing.CompanionPairingServer
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget

/**
 * App-wide host for the nearby-TV offer (iOS shows its card app-wide too).
 * Shown only where the user is signed in and not in the middle of playback;
 * [enabled] is decided by the navigation host.
 */
@Composable
fun CompanionPairingHost(
    enabled: Boolean,
    viewModel: CompanionPairingViewModel = org.koin.compose.viewmodel.koinViewModel(),
) {
    LaunchedEffect(enabled) { viewModel.setActive(enabled) }
    val offers by viewModel.offers.collectAsState()
    val status by viewModel.status.collectAsState()
    val approval by viewModel.pendingApproval.collectAsState()
    val serverChoices by viewModel.serverChoices.collectAsState()
    var presented by remember { mutableStateOf<CompanionOffer?>(null) }
    LaunchedEffect(offers, status, enabled) {
        // A route that hides the offer hides it mid-pairing too; the view
        // model cancels that pairing (setActive).
        if (!enabled) {
            presented = null
            return@LaunchedEffect
        }
        if (status !is CompanionPairingStatus.Idle) return@LaunchedEffect
        val current = presented
        presented = if (current == null) {
            offers.firstOrNull()
        } else {
            // Android NSD can resolve the same TV again with a new listener
            // port after its screen restarts. Keep the visible card, but pair
            // with the freshest endpoint.
            offers.firstOrNull { it.target.deviceId == current.target.deviceId } ?: offers.firstOrNull()
        }
    }
    CompanionPairingBottomOverlay(
        target = presented?.target,
        signInServerName = presented?.signInServer?.displayName,
        status = status,
        approval = approval,
        serverChoices = serverChoices,
        onPair = { presented?.let(viewModel::pair) },
        onServersSelected = viewModel::continueWithServers,
        onApprove = viewModel::approveMatchCode,
        onDecline = viewModel::cancelMatchCode,
        onDismiss = {
            // Every close hides this TV's session, or the card would come
            // straight back once the status returns to Idle.
            val offer = presented
            if (offer != null) viewModel.dismiss(offer.target) else viewModel.dismissPairing()
            presented = null
        },
    )
}

/** Bottom-anchored companion setup card matching the iOS presentation. */
@Composable
fun CompanionPairingBottomOverlay(
    target: CompanionPairingTarget?,
    signInServerName: String? = null,
    status: CompanionPairingStatus,
    approval: CompanionPairingApproval?,
    serverChoices: List<CompanionPairingServer>?,
    onPair: () -> Unit,
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
                    signInServerName = signInServerName,
                    status = status,
                    approval = approval,
                    serverChoices = serverChoices,
                    onPair = onPair,
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
    signInServerName: String?,
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

    // A signed-out TV (`st=login`) is being signed in, not set up; the
    // progress and result copy says so.
    val signIn = signInServerName != null
    val paneTitle = stringResource(R.string.companion_pane_title)

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
            .semantics { this.paneTitle = paneTitle },
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
                    signInServerName = signInServerName,
                    onPair = onPair,
                    onDismiss = onDismiss,
                )
                status is CompanionPairingStatus.Completed -> TerminalStep(
                    successful = true,
                    title = if (signIn) {
                        stringResource(R.string.companion_signin_done_title, status.targetName)
                    } else {
                        status.serverNames.takeIf { it.isNotEmpty() }
                            ?.let { stringResource(R.string.companion_setup_done_title, it.joinToString()) }
                            ?: stringResource(R.string.companion_setup_done_title_generic)
                    },
                    message = if (signIn) {
                        stringResource(R.string.companion_signin_done_body, signInServerName.orEmpty())
                    } else {
                        stringResource(R.string.companion_setup_done_body, status.targetName)
                    },
                    primaryLabel = stringResource(R.string.companion_done),
                    onPrimary = onDismiss,
                )
                status is CompanionPairingStatus.Failed -> TerminalStep(
                    successful = false,
                    title = if (signIn) {
                        stringResource(R.string.companion_signin_failed_title)
                    } else {
                        stringResource(R.string.companion_setup_failed_title)
                    },
                    message = status.message,
                    primaryLabel = stringResource(R.string.companion_try_again),
                    onPrimary = onPair,
                    onSecondary = onDismiss,
                )
                else -> ProgressStep(status = status, signIn = signIn, onCancel = onDismiss)
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
    // The phone's active server is preselected (the usual answer).
    var selectedIds by remember(servers) {
        mutableStateOf(servers.filter { it.isActive }.map { it.id }.toSet())
    }
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
                Text(stringResource(R.string.companion_choose_servers_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.companion_choose_servers_body, targetName),
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
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val address = companionServerAddressLabel(server.url)
                        // Two servers can share a display name, so the address is
                        // what actually tells them apart. Skipped when the name is
                        // already the address and the line would just repeat it.
                        if (address != null && !address.equals(server.displayName, ignoreCase = true)) {
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
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
            Text(stringResource(R.string.companion_continue), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.companion_cancel))
        }
    }
}

/**
 * Strips the parts of a server URL that carry no information for the reader.
 * `https://` goes because it is the default; a plain `http://` stays because
 * it is the exception worth noticing. The port and path survive — they are
 * often the only difference between two servers on one host.
 */
internal fun companionServerAddressLabel(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    val withoutScheme = if (trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed.substring("https://".length)
    } else {
        trimmed
    }
    return withoutScheme.takeIf { it.isNotBlank() }
}

@Composable
private fun DiscoveryStep(
    target: CompanionPairingTarget,
    signInServerName: String?,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A signed-out TV wants one server this phone holds: "Sign in <TV>?".
    // A first-run TV gets the setup offer and the server chooser.
    val signIn = signInServerName != null
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
            text = if (signIn) {
                stringResource(R.string.companion_offer_signin_title, target.name, signInServerName.orEmpty())
            } else {
                stringResource(R.string.companion_offer_setup_title, target.name)
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (signInServerName != null) {
                stringResource(R.string.companion_offer_signin_body, target.name)
            } else {
                stringResource(R.string.companion_offer_setup_body, target.name)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(
            label = stringResource(
                if (signIn) R.string.companion_offer_signin_action else R.string.companion_offer_setup_action,
            ),
            onClick = onPair,
            modifier = Modifier.padding(top = 14.dp),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.companion_offer_not_now))
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
        // People compare the TV's sign-in code, the one it shows on screen;
        // the match words are checked in code and never shown.
        Text(
            text = stringResource(R.string.companion_confirm_check, approval.targetName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val spoken = DeviceCodeFormat.spoken(approval.userCode)
        Text(
            text = DeviceCodeFormat.display(approval.userCode),
            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        )
        // TV apps released before user codes show only the match words.
        // Remove this line together with the web /activate card's
        // "Older TV apps show ..." line.
        approval.serverMatchCode.trim().takeIf { it.isNotEmpty() }?.let { words ->
            Text(
                text = stringResource(R.string.companion_confirm_older_tv, words.uppercase()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // The same content model as "Sign in a TV", the web /activate card
        // and the iPhone app: which server and account, what approving
        // grants, and the warning.
        Text(
            text = companionSignInLine(approval),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.sign_in_tv_profiles_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.sign_in_tv_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(
            label = stringResource(R.string.companion_confirm_yes),
            onClick = onApprove,
            modifier = Modifier.padding(top = 14.dp),
        )
        TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.companion_confirm_no))
        }
    }
}

/** The same line as the code-entry approval ([tvSignInLine]). */
@Composable
private fun companionSignInLine(approval: CompanionPairingApproval): String =
    tvSignInLine(approval.serverName, approval.serverHost, approval.accountName) ?: approval.serverName

@Composable
private fun ProgressStep(status: CompanionPairingStatus, signIn: Boolean, onCancel: () -> Unit) {
    val working = stringResource(
        if (signIn) R.string.companion_signin_progress_title else R.string.companion_setup_progress_title,
    )
    val connecting = stringResource(R.string.companion_connecting)
    val (title, message) = when (status) {
        is CompanionPairingStatus.Connecting -> connecting to status.targetName
        is CompanionPairingStatus.PickServers -> connecting to status.targetName
        is CompanionPairingStatus.PushingServer -> working to if (signIn) {
            stringResource(R.string.companion_signin_progress_allow, status.targetName)
        } else {
            stringResource(R.string.companion_setup_progress_allow, status.targetName)
        }
        is CompanionPairingStatus.AwaitingMatchConfirmation ->
            working to stringResource(R.string.companion_progress_waiting_code, status.approval.targetName)
        is CompanionPairingStatus.Approving ->
            working to stringResource(R.string.companion_progress_approving, status.serverName, status.targetName)
        is CompanionPairingStatus.SignedIn ->
            working to stringResource(R.string.companion_progress_signed_in, status.serverName)
        else -> working to stringResource(R.string.companion_progress_wait)
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
            Text(stringResource(R.string.companion_cancel))
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
                Text(stringResource(R.string.companion_close))
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
