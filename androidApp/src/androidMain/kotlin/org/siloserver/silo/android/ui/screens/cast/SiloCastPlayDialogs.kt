package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import org.siloserver.silo.android.cast.SiloCastPlayRouter

/**
 * The two questions [SiloCastPlayRouter] asks before a Play, worded as on
 * iOS: replacing the title the TV is showing, and where a download should
 * play while a TV is engaged. Rendered once, at the root of the nav graph,
 * with that graph's current navigation: [onOpenRemote] after a title goes
 * to the TV, [onPlayHere] with a player route for the phone.
 */
@Composable
fun SiloCastPlayDialogs(
    onOpenRemote: () -> Unit,
    onPlayHere: (route: String) -> Unit,
    router: SiloCastPlayRouter = koinInject(),
) {
    val replace by router.pendingReplace.collectAsState()
    val offline by router.pendingOffline.collectAsState()

    fun go(destination: SiloCastPlayRouter.Destination?) {
        when (destination) {
            SiloCastPlayRouter.Destination.Tv -> onOpenRemote()
            is SiloCastPlayRouter.Destination.Here -> onPlayHere(destination.route)
            null -> Unit
        }
    }

    replace?.let { choice ->
        AlertDialog(
            onDismissRequest = router::dismissReplace,
            title = { Text("Replace what's playing?") },
            text = { Text("${choice.targetName} is playing ${choice.currentTitle}. Playing this will stop it.") },
            confirmButton = {
                TextButton(onClick = { go(router.confirmReplace()) }) { Text("Play on ${choice.targetName}") }
            },
            dismissButton = {
                TextButton(onClick = router::dismissReplace) { Text("Cancel") }
            },
        )
    }

    offline?.let { choice ->
        AlertDialog(
            onDismissRequest = router::dismissOffline,
            title = { Text("A TV is connected") },
            text = { Text("Downloads only play on this device. The TV can stream the same title from your server.") },
            confirmButton = {
                TextButton(onClick = { go(router.sendOfflineToTv()) }) { Text("Play on ${choice.targetName}") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = router::dismissOffline) { Text("Cancel") }
                    TextButton(onClick = { go(router.playOfflineHere()) }) { Text("Play on this device") }
                }
            },
        )
    }
}
