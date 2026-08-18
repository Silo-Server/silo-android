package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloMutedText
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer

/**
 * The one confirmation dialog.
 *
 * Extracted from the "Remove all downloads?" dialog that was inline in
 * `SettingsScreen`, because sign-out now needs the identical gate from two
 * unrelated places and a third hand-rolled `AlertDialog` is how a surface ends
 * up with three reds and three button orders.
 *
 * Cancel is the safe choice and sits where M3 puts the dismissive action; the
 * confirm button carries [SiloDestructive] when the action destroys or
 * discards something.
 */
@Composable
fun SiloConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(SettingsDimens.cardRadius),
        containerColor = SiloSurfaceContainer,
        titleContentColor = SiloForeground,
        textContentColor = SiloMutedText,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                enabled = confirmEnabled,
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) SiloDestructive else SiloForeground,
                    disabledContentColor = (if (destructive) SiloDestructive else SiloForeground)
                        .copy(alpha = SettingsDimens.disabledAlpha),
                ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SiloForeground),
            ) {
                Text(dismissLabel)
            }
        },
    )
}

/**
 * The sign-out gate, shared by both places that can sign this device out: the
 * profile menu in the top bar, and the Sign out row in the settings Account
 * card. Neither confirmed before, and gating only one of them would make the
 * app's answer to "are you sure?" depend on which button the user happened to
 * reach for.
 *
 * The body states what sign-out actually does, which is less than users tend
 * to assume: `AuthRepository.logout` clears the tokens and profile state for
 * the active server and deliberately keeps its `ServerRegistry` entry, and
 * downloaded files are only ever deleted by `OrphanedServerDataPurger`, which
 * fires on a server being *removed from the registry* — never on sign-out. So
 * the copy promises the downloads and the saved server survive, because they
 * do.
 *
 * @param accountName Named in the body where the caller knows it. The settings
 *   Account card has the signed-in [org.siloserver.silo.model.auth.User]; the
 *   top bar knows only the active profile, which is not the account being
 *   signed out and must not be substituted for it.
 */
@Composable
fun SignOutConfirmDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    accountName: String? = null,
) {
    if (!visible) return
    SiloConfirmDialog(
        title = "Sign out?",
        body = buildString {
            append("This signs this device out of ")
            append(if (accountName.isNullOrBlank()) "your account" else "$accountName's account")
            append(". Downloads stay on this device and the server stays saved, ")
            append("so you can sign back in without setting it up again.")
        },
        confirmLabel = "Sign out",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
