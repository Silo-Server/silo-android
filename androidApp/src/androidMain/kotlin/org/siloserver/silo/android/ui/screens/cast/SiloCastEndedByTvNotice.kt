package org.siloserver.silo.android.ui.screens.cast

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        controller.endedByTv.collect { notice ->
            latestCloseRemote()
            // Only while Silo is on screen: the TV also ends sessions nobody
            // is watching, and the toast must not land over another app.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Toast.makeText(context, notice.resolve(context.resources), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
