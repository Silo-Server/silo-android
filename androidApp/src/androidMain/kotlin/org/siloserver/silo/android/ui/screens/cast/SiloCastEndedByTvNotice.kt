package org.siloserver.silo.android.ui.screens.cast

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.siloserver.silo.android.cast.SiloCastController

/**
 * When the TV ends the Remote Control session for good (its viewer took
 * control back), closes the remote and says so, wherever the user is in the
 * app. [closeRemote] closes the remote screen if it is open.
 */
@Composable
fun SiloCastEndedByTvNotice(controller: SiloCastController, closeRemote: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestCloseRemote by rememberUpdatedState(closeRemote)
    LaunchedEffect(controller, context, lifecycle) {
        // Only while Silo is on screen: a close that lands while the app is in
        // the background waits in the controller's channel, so the user comes
        // back to the notice rather than to a remote that vanished unexplained,
        // and the toast never lands over another app.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.endedByTv.collect { notice ->
                // A TV the user picked since then keeps its remote open.
                val current = controller.state.value
                if (current.connectedTarget == null && !current.isConnecting && !current.isReconnecting) {
                    latestCloseRemote()
                }
                Toast.makeText(context, notice.resolve(context.resources), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
