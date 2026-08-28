package org.siloserver.silo.tv

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.siloserver.silo.common.di.playerInfraModule
import org.siloserver.silo.common.di.playerModule
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsStartup
import org.siloserver.silo.common.diagnostics.diagnosticsModule
import org.siloserver.silo.di.sharedModules
import org.siloserver.silo.tv.di.androidTvModule
import org.siloserver.silo.tv.watchnext.TvWorkerFactory
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.siloserver.silo.common.images.buildSiloImageLoader

/**
 * Implements `Configuration.Provider` rather than installing Koin's
 * `workManagerFactory()` inside `startKoin { … }`. The Koin path loses the
 * race against WorkManager's androidx.startup auto-init (which runs before
 * `Application.onCreate` on many devices), silently leaving WorkManager on
 * its reflection-based factory — which cannot construct
 * [org.siloserver.silo.tv.watchnext.WatchNextSyncWorker]'s injected
 * constructor. Mirrors the phone app's SiloApplication +
 * AppWorkerFactory recipe (see that file for the full history).
 */
class SiloTvApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStartup.installCrashCapture(this)
        val koinApp = startKoin {
            androidContext(this@SiloTvApplication)
            modules(sharedModules() + playerModule + playerInfraModule + androidTvModule + diagnosticsModule)
        }
        DiagnosticsStartup.startCoordinator { koinApp.koin.get<DiagnosticsCoordinator>() }
        // Live-home socket (Apple realtime-updates spec). Guarded — a dead
        // socket just means Home refreshes on open only.
        runCatching {
            org.siloserver.silo.tv.home.HomeRealtimeForegroundStarter(
                coordinator = koinApp.koin.get(),
                profileRepository = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("SiloTvApplication", "Home realtime starter init failed", it)
        }
        // The androidx.startup WorkManagerInitializer is opted out in the
        // manifest; force-initialise explicitly with TvWorkerFactory now
        // that Koin is up. runCatching guards the "already initialised"
        // IllegalStateException in case anything else got there first.
        runCatching {
            WorkManager.initialize(this, workManagerConfiguration)
            android.util.Log.i("SiloTvApplication", "WorkManager.initialize called with TvWorkerFactory")
        }.onFailure {
            android.util.Log.w("SiloTvApplication", "WorkManager.initialize failed (already initialised?)", it)
        }
        // Drain the user-state outbox (Track B) on launch + when connectivity
        // returns. Guarded — never load-bearing for cold start.
        runCatching {
            org.siloserver.silo.common.data.sync.OutboxSyncStarter(
                context = this@SiloTvApplication,
                registry = koinApp.koin.get(),
            ).start()
        }.onFailure {
            android.util.Log.w("SiloTvApplication", "Outbox sync starter init failed", it)
        }
        // Reclaim the disk and Room rows of servers the user has removed.
        // Derived from "rows with no registry entry", so it is idempotent, also
        // cleans installs that removed servers before this existed, and cannot
        // strand anything if a pass is interrupted. Guarded — never load-bearing
        // for cold start.
        runCatching {
            org.siloserver.silo.common.downloads.installOrphanedServerDataPurge(
                context = this@SiloTvApplication,
                registry = koinApp.koin.get(),
                database = koinApp.koin.get(),
                storage = koinApp.koin.get(),
            )
        }.onFailure {
            android.util.Log.w("SiloTvApplication", "Orphaned server purge init failed", it)
        }
        // One-time migration: drain the legacy .record.json download sidecar tree
        // into Room so pre-cutover downloads keep their metadata. Guarded — runs
        // off the main thread, never load-bearing for cold start.
        runCatching {
            val importer = koinApp.koin.get<org.siloserver.silo.common.downloads.LegacyDownloadImporter>()
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                runCatching { importer.awaitImport(System.currentTimeMillis()) }
                    .onFailure { android.util.Log.w("SiloTvApplication", "Legacy download import failed", it) }
            }
        }.onFailure {
            android.util.Log.w("SiloTvApplication", "Legacy download importer init failed", it)
        }
    }

    override val workManagerConfiguration: Configuration
        get() {
            android.util.Log.i("SiloTvApplication", "workManagerConfiguration accessed; installing TvWorkerFactory")
            return Configuration.Builder()
                .setWorkerFactory(TvWorkerFactory())
                .build()
        }

    /** Coil setup is shared with the phone app — see [buildSiloImageLoader]. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        buildSiloImageLoader(context, cacheDir)
}
