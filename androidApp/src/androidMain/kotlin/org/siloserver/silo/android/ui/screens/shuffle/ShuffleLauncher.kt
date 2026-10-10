package org.siloserver.silo.android.ui.screens.shuffle

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.common.ui.ShuffleLauncher
import org.siloserver.silo.model.shuffle.Shuffle

/** A [ShuffleLauncher] that calls [onStarted] with each shuffle it starts. */
@Composable
fun rememberShuffleLauncher(onStarted: (Shuffle) -> Unit): ShuffleLauncher =
    org.siloserver.silo.common.ui.rememberShuffleLauncher(koinInject(), onStarted)

/** The player route for a shuffle's first pick, which plays from the beginning. */
fun shufflePlayerRoute(shuffle: Shuffle, libraryId: Int? = null): String =
    Route.Player(
        contentId = shuffle.current.contentId,
        resumePositionSeconds = 0.0,
        libraryId = libraryId,
        shuffleId = shuffle.id,
    ).route
