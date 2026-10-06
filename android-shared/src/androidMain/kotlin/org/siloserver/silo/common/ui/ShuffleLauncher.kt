package org.siloserver.silo.common.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.siloserver.silo.model.feature.ShuffleFeatureStore
import org.siloserver.silo.model.feature.ShuffleStart
import org.siloserver.silo.model.shuffle.Shuffle
import org.siloserver.silo.model.shuffle.ShuffleCapability
import org.siloserver.silo.model.shuffle.ShuffleScopeKind

/**
 * Starts shuffles from an entry point: offers a scope kind only when the
 * server's capability allows it, ignores presses while a start is in flight,
 * and reports a failure as a toast.
 */
class ShuffleLauncher internal constructor(
    private val store: ShuffleFeatureStore,
    private val scope: CoroutineScope,
    private val context: Context,
    private val onStarted: State<(Shuffle) -> Unit>,
    private val capability: State<ShuffleCapability>,
) {
    var isStarting by mutableStateOf(false)
        private set

    fun supports(kind: ShuffleScopeKind): Boolean = capability.value.supports(kind)

    fun start(kind: ShuffleScopeKind, id: String) {
        if (isStarting || id.isBlank()) return
        isStarting = true
        scope.launch {
            try {
                when (val result = store.start(kind, id)) {
                    is ShuffleStart.Started -> onStarted.value(result.shuffle)
                    is ShuffleStart.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                isStarting = false
            }
        }
    }
}

/** A [ShuffleLauncher] that calls [onStarted] with each shuffle it starts. */
@Composable
fun rememberShuffleLauncher(store: ShuffleFeatureStore, onStarted: (Shuffle) -> Unit): ShuffleLauncher {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val latestOnStarted = rememberUpdatedState(onStarted)
    val capability = store.capability.collectAsState()
    return remember(store, scope, context) {
        ShuffleLauncher(store, scope, context, latestOnStarted, capability)
    }
}
