package org.siloserver.silo.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.crossfade
import org.siloserver.silo.android.di.androidModule
import org.siloserver.silo.android.downloads.AppWorkerFactory
import org.siloserver.silo.android.notifications.NotificationsForegroundStarter
import org.siloserver.silo.android.push.AndroidPushRegistrationStarter
import org.siloserver.silo.common.di.playerInfraModule
import org.siloserver.silo.common.di.playerModule
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsStartup
import org.siloserver.silo.common.diagnostics.diagnosticsModule
import org.siloserver.silo.common.downloads.DownloadWorker
import org.siloserver.silo.di.sharedModules
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Implements `Configuration.Provider` rather than calling
 * `workManagerFactory()` inside `startKoin { … }`. The latter only wins if
 * WorkManager hasn't been auto-initialised yet (and the androidx.startup
 * provider initialises it before `Application.onCreate` runs on many devices),
 * which silently leaves WorkManager using its reflection-based factory and
 * crashes when constructing DI-built workers like DownloadWorker. The
 * Provider path is the modern, reliable recipe.
 */
class SiloApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStartup.installCrashCapture(this)
        val koinApp = startKoin {
            androidContext(this@SiloApplication)
            modules(sharedModules() + playerModule + playerInfraModule + androidModule + diagnosticsModule)
        }
        DiagnosticsStartup.startCoordinator { koinApp.koin.get<DiagnosticsCoordinator>() }
        // Drive notifications realtime off the app foreground lifecycle. Guarded:
        // it's a foreground accelerator, never load-bearing for cold start.
        runCatching {
            NotificationsForegroundStarter(
                repository = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("SiloApplication", "Notifications starter init failed", it)
        }
        runCatching {
            AndroidPushRegistrationStarter(
                registrar = koinApp.koin.get(),
                notificationsRepository = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("SiloApplication", "Android push registration starter init failed", it)
        }
        // SiloCast session lifecycle: foreground pings a live TV session and
        // probes for a silent auto-resume of the last-controlled TV; background
        // drops unengaged auto-resumed sessions. Guarded — never load-bearing.
        runCatching {
            org.siloserver.silo.android.cast.SiloCastForegroundStarter(
                controller = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("SiloApplication", "SiloCast foreground starter init failed", it)
        }
        runCatching {
            org.siloserver.silo.android.cast.SiloCastMediaSessionStarter(
                context = this@SiloApplication,
                controller = koinApp.koin.get(),
            ).start()
        }.onFailure {
            android.util.Log.w("SiloApplication", "SiloCast media-session starter init failed", it)
        }
        // Configuration.Provider wasn't reliably picked up by WM's androidx.startup
        // auto-init (the auto-init seemed to win the race, leaving WM with its
        // default reflection-based WorkerFactory). Force-initialise explicitly
        // with our AppWorkerFactory now that Koin is up.
        runCatching {
            WorkManager.initialize(this, workManagerConfiguration)
            android.util.Log.i("SiloApplication", "WorkManager.initialize called with AppWorkerFactory")
        }.onFailure {
            android.util.Log.w("SiloApplication", "WorkManager.initialize failed (already initialised?)", it)
        }
        // Live-home socket (Apple realtime-updates spec). Guarded — a dead
        // socket degrades to pull-to-refresh behavior.
        runCatching {
            org.siloserver.silo.android.home.HomeRealtimeForegroundStarter(
                coordinator = koinApp.koin.get(),
                profileRepository = koinApp.koin.get(),
            ).register()
        }.onFailure {
            android.util.Log.w("SiloApplication", "Home realtime starter init failed", it)
        }
        // Drain the user-state outbox (Track B) on launch + when connectivity
        // returns. Guarded — never load-bearing for cold start.
        runCatching {
            org.siloserver.silo.common.data.sync.OutboxSyncStarter(
                context = this@SiloApplication,
                registry = koinApp.koin.get(),
            ).start()
        }.onFailure {
            android.util.Log.w("SiloApplication", "Outbox sync starter init failed", it)
        }
        // Reclaim the disk and Room rows of servers the user has removed.
        // Derived from "rows with no registry entry", so it is idempotent, also
        // cleans installs that removed servers before this existed, and cannot
        // strand anything if a pass is interrupted. Guarded — never load-bearing
        // for cold start.
        runCatching {
            org.siloserver.silo.common.downloads.installOrphanedServerDataPurge(
                context = this@SiloApplication,
                registry = koinApp.koin.get(),
                database = koinApp.koin.get(),
                storage = koinApp.koin.get(),
            )
        }.onFailure {
            android.util.Log.w("SiloApplication", "Orphaned server purge init failed", it)
        }
        // One-time migration: drain the legacy .record.json download sidecar tree
        // into Room so pre-cutover downloads keep their metadata. Guarded — never
        // load-bearing for cold start; runs off the main thread.
        runCatching {
            val importer = koinApp.koin.get<org.siloserver.silo.common.downloads.LegacyDownloadImporter>()
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                runCatching { importer.awaitImport(System.currentTimeMillis()) }
                    .onFailure { android.util.Log.w("SiloApplication", "Legacy download import failed", it) }
            }
        }.onFailure {
            android.util.Log.w("SiloApplication", "Legacy download importer init failed", it)
        }
        // Keep monitored-download subscriptions evaluated in the background.
        // The periodic request carries no scope input, so the worker follows
        // whatever server/profile is active when it fires. Guarded — never
        // load-bearing for cold start.
        runCatching {
            org.siloserver.silo.common.downloads.DownloadSubscriptionWorker.enqueuePeriodic(this)
        }.onFailure {
            android.util.Log.w("SiloApplication", "Download subscription periodic enqueue failed", it)
        }
        registerDownloadsNotificationChannel()
    }

    override val workManagerConfiguration: Configuration
        get() {
            android.util.Log.i("SiloApplication", "workManagerConfiguration accessed; installing AppWorkerFactory")
            return Configuration.Builder()
                .setWorkerFactory(AppWorkerFactory())
                .build()
        }

    /**
     * Tunes the shared Coil image loader: a generous on-disk artwork cache so
     * posters/backdrops survive between sessions (Coil's default disk cap is
     * small — 2% of free space, capped at 250MB). Memory cache and network
     * stack stay at Coil's heap-proportional defaults.
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

    /**
     * Channel for offline download progress / completion notifications.
     * Required on API 26+ before any notification can be posted. Low
     * importance so the user isn't interrupted while we silently
     * download bytes in the background.
     */
    private fun registerDownloadsNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            DownloadWorker.NOTIFICATION_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress and completion for offline downloads"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
