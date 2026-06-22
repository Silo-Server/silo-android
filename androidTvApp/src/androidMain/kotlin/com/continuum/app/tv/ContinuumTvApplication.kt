package com.continuum.app.tv

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.crossfade
import com.continuum.app.common.di.playerInfraModule
import com.continuum.app.common.di.playerModule
import com.continuum.app.di.sharedModules
import com.continuum.app.tv.di.androidTvModule
import com.continuum.app.tv.notifications.NotificationsForegroundStarter
import com.continuum.app.tv.watchnext.TvWorkerFactory
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Implements `Configuration.Provider` rather than installing Koin's
 * `workManagerFactory()` inside `startKoin { … }`. The Koin path loses the
 * race against WorkManager's androidx.startup auto-init (which runs before
 * `Application.onCreate` on many devices), silently leaving WorkManager on
 * its reflection-based factory — which cannot construct
 * [com.continuum.app.tv.watchnext.WatchNextSyncWorker]'s injected
 * constructor. Mirrors the phone app's ContinuumApplication +
 * AppWorkerFactory recipe (see that file for the full history).
 */
class ContinuumTvApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        val koinApp = startKoin {
            androidContext(this@ContinuumTvApplication)
            modules(sharedModules() + playerModule + playerInfraModule + androidTvModule)
        }
        // Notifications realtime: connect while foregrounded. Separate app
        // module → own Koin start, so TV invokes the starter here (the phone app
        // does the equivalent in ContinuumApplication). Guarded: it's a
        // foreground accelerator, never load-bearing for cold start.
        runCatching {
            NotificationsForegroundStarter(
                repository = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("ContinuumTvApplication", "Notifications realtime starter failed", it)
        }
        // The androidx.startup WorkManagerInitializer is opted out in the
        // manifest; force-initialise explicitly with TvWorkerFactory now
        // that Koin is up. runCatching guards the "already initialised"
        // IllegalStateException in case anything else got there first.
        runCatching {
            WorkManager.initialize(this, workManagerConfiguration)
            android.util.Log.i("ContinuumTvApplication", "WorkManager.initialize called with TvWorkerFactory")
        }.onFailure {
            android.util.Log.w("ContinuumTvApplication", "WorkManager.initialize failed (already initialised?)", it)
        }
        // Drain the user-state outbox (Track B) on launch + when connectivity
        // returns. Guarded — never load-bearing for cold start.
        runCatching {
            com.continuum.app.common.data.sync.OutboxSyncStarter(
                context = this@ContinuumTvApplication,
                registry = koinApp.koin.get(),
            ).start()
        }.onFailure {
            android.util.Log.w("ContinuumTvApplication", "Outbox sync starter init failed", it)
        }
        // One-time migration: drain the legacy .record.json download sidecar tree
        // into Room so pre-cutover downloads keep their metadata. Guarded — runs
        // off the main thread, never load-bearing for cold start.
        runCatching {
            val importer = koinApp.koin.get<com.continuum.app.common.downloads.LegacyDownloadImporter>()
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                runCatching { importer.awaitImport(System.currentTimeMillis()) }
                    .onFailure { android.util.Log.w("ContinuumTvApplication", "Legacy download import failed", it) }
            }
        }.onFailure {
            android.util.Log.w("ContinuumTvApplication", "Legacy download importer init failed", it)
        }
    }

    override val workManagerConfiguration: Configuration
        get() {
            android.util.Log.i("ContinuumTvApplication", "workManagerConfiguration accessed; installing TvWorkerFactory")
            return Configuration.Builder()
                .setWorkerFactory(TvWorkerFactory())
                .build()
        }

    /**
     * Tunes the shared Coil image loader with a generous on-disk artwork cache
     * so posters/backdrops survive between sessions. Mirrors the phone app.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
}
