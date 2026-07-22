package org.siloserver.silo.common.di

import android.content.Context
import android.content.pm.PackageInfo
import org.siloserver.silo.common.diagnostics.DeviceSnapshotCollector
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.common.diagnostics.PlaybackDiagnosticsLogger
import org.siloserver.silo.common.diagnostics.SiloLogNetworkDiagnosticsHook
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsConsentStore
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsProfileGate
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsSettingsStore
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.common.diagnostics.consent.RecentSessionTracker
import org.siloserver.silo.common.diagnostics.crash.CrashCapture
import org.siloserver.silo.common.diagnostics.crash.CrashMarkerAssembler
import org.siloserver.silo.common.diagnostics.crash.ExitInfoCollector
import org.siloserver.silo.common.diagnostics.bundle.DiagnosticsBundleBuilder
import org.siloserver.silo.common.diagnostics.logging.BreadcrumbJournal
import org.siloserver.silo.common.diagnostics.logging.DiagnosticsFileLogger
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.network.NetworkDiagnosticsHook
import org.siloserver.silo.repository.ProfileRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Diagnostics wiring shared by both apps. [platform] is `android` or
 * `android-tv` (matching the report manifest's platform field); everything
 * else derives from it or from the app context.
 */
fun diagnosticsModule(platform: String) = module {
    single { DiagnosticsSettingsStore(androidContext()) }
    single { DiagnosticsConsentStore(diagnosticsDir(androidContext())) }
    single { PendingReportStore(diagnosticsDir(androidContext())) }
    single { RecentSessionTracker(diagnosticsDir(androidContext())) }
    single { DiagnosticsFileLogger(diagnosticsDir(androidContext())) }
    single { BreadcrumbJournal(diagnosticsDir(androidContext())) }
    single { DiagnosticsBundleBuilder() }
    single<NetworkDiagnosticsHook> { SiloLogNetworkDiagnosticsHook() }
    single {
        DeviceSnapshotCollector(
            context = androidContext(),
            audioCapabilityManager = get<AudioCapabilityManager>(),
            deviceMetadataProvider = get(),
            isTv = platform == "android-tv",
        )
    }
    single {
        DiagnosticsProfileGate { profileId ->
            get<ProfileRepository>().listProfiles().let { result ->
                when (result) {
                    is org.siloserver.silo.network.ApiResult.Success ->
                        result.data.firstOrNull { it.id == profileId }?.isChild
                    else -> null
                }
            }
        }
    }
    single {
        CrashMarkerAssembler(
            markerDir = CrashCapture.markerDir(androidContext()),
            pendingReportStore = get(),
            deviceSnapshotCollector = get(),
            breadcrumbJournal = get(),
        )
    }
    single {
        ExitInfoCollector(
            context = androidContext(),
            pendingReportStore = get(),
            deviceSnapshotCollector = get(),
            breadcrumbJournal = get(),
        )
    }
    single {
        PlaybackDiagnosticsLogger(
            sinkTypeProvider = { runCatching { get<AudioCapabilityManager>().currentSinkType() }.getOrNull() },
            statsTimelineEnabled = {
                get<DiagnosticsSettingsStore>().debugLogging.value ||
                    get<DiagnosticsCoordinator>().state.value.captureActive
            },
        )
    }
    single {
        val versionInfo = appVersionInfo(androidContext())
        DiagnosticsCoordinator(
            platform = platform,
            appVersion = versionInfo.first,
            appBuild = versionInfo.second,
            repository = get(),
            featureStore = get(),
            consentStore = get(),
            pendingReportStore = get(),
            settingsStore = get(),
            sessionTracker = get(),
            deviceSnapshotCollector = get(),
            bundleBuilder = get(),
            fileLogger = get(),
            breadcrumbJournal = get(),
            markerAssembler = get(),
            exitInfoCollector = get(),
            tokenManager = get(),
            authRepository = get(),
            profileGate = get(),
            activeProfileProvider = {
                get<ProfileRepository>().getActiveProfile()?.let {
                    DiagnosticsCoordinator.ActiveProfileInfo(id = it.id, isChild = it.isChild)
                }
            },
        )
    }
    viewModel { DiagnosticsViewModel(get()) }
    // Account-lifecycle events consumed by AuthRepository / server management.
    // The coordinator is resolved lazily inside the callbacks — resolving it
    // here would cycle (coordinator depends on AuthRepository, which takes
    // this binding).
    single<org.siloserver.silo.model.feature.DiagnosticsAccountEvents> {
        val koin = getKoin()
        object : org.siloserver.silo.model.feature.DiagnosticsAccountEvents {
            override fun onSignedOut(localServerId: String?) {
                koin.get<DiagnosticsCoordinator>().onSignOut(localServerId)
            }

            override fun onServerRemoved(localServerId: String) {
                koin.get<DiagnosticsCoordinator>().onServerRemoved(localServerId)
            }
        }
    }
}

private fun diagnosticsDir(context: Context): File = File(context.noBackupFilesDir, "diagnostics")

@Suppress("DEPRECATION")
private fun appVersionInfo(context: Context): Pair<String, String> {
    val info: PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = info?.versionName?.trim().orEmpty().ifBlank { "unknown" }
    val versionCode = info?.let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else it.versionCode.toString()
    } ?: "unknown"
    return versionName to versionCode
}
