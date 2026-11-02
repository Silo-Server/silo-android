package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.focus.tvControlSemantics

/**
 * After signing in with a temporary password an admin set. The server
 * refuses everything but the password change until the account chooses its
 * own, and three passwords are a lot to type with a remote, so the TV points
 * at the phone app or the web and offers sign-out (silo-apple
 * `TVPasswordChangeView`). Changing the password there ends this TV's
 * session, so Sign out is the way forward.
 */
@Composable
internal fun TvPasswordChangeRequiredScreen(
    accountName: String?,
    onSignOut: () -> Unit,
) {
    val signOutFocus = remember { FocusRequester() }
    val initialFocus = rememberTvContentInitialFocus(target = signOutFocus, contentKey = Unit)
    // Pressed once, Sign out keeps focus but does nothing more while it runs.
    var signingOut by remember { mutableStateOf(false) }
    val signOutControl = TvControlState.transient(isEnabled = !signingOut)
    val account = accountName?.ifBlank { null } ?: "this account"
    Box(Modifier.fillMaxSize().then(initialFocus)) {
        TvMarqueeScreen(
            copy = {
                TvMarqueeHeadline("Choose a new\npassword")
                TvMarqueeBody(
                    "An admin set a temporary password for $account. Choose your own in Silo on your phone, " +
                        "or in a web browser, then sign in here with it.",
                    modifier = Modifier.padding(top = 11.dp),
                )
                TvMarqueeButton(
                    text = "Sign out",
                    onClick = {
                        signOutControl.perform {
                            signingOut = true
                            onSignOut()
                        }
                    },
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    focusRequester = signOutFocus,
                    modifier = Modifier.padding(top = 28.dp).tvControlSemantics(signOutControl),
                )
            },
            card = {},
        )
    }
}
