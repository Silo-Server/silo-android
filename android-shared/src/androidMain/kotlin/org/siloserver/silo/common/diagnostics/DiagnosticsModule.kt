package org.siloserver.silo.common.diagnostics

import android.content.res.Configuration
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.network.NetworkDiagnosticsObserver
import org.siloserver.silo.network.DiagnosticsUploadAuthorization
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.DefaultHostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.createHostedDiagnosticsClient

private val DIAGNOSTICS_DATA_STORE = named("diagnostics-data-store")
private val DIAGNOSTICS_SCOPE = named("diagnostics-scope")
private val SELF_HOSTED_DIAGNOSTICS_IDENTITY = named("self-hosted-diagnostics-identity")
private val HOSTED_DIAGNOSTICS_HTTP = named("hosted-diagnostics-http")

val diagnosticsModule = module {
    single<NetworkDiagnosticsObserver> { DiagnosticsNetworkLogger }
    single<org.siloserver.silo.viewmodel.HomeDiagnosticsObserver> { DiagnosticsHomeLoadObserver }
    single<DataStore<Preferences>>(DIAGNOSTICS_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("silo_diagnostics") },
        )
    }
    single<PendingReportStore> { FilePendingReportStore(androidContext().noBackupFilesDir) }
    single { DiagnosticsPrivacyBarrier() }
    single<DiagnosticsLogBuffer> { LogRing() }
    single { DiagnosticsPlaybackSessionTracker() }
    single {
        AndroidDiagnosticsPerformanceRecorder(androidContext().applicationContext as android.app.Application).also {
            it.install()
        }
    }
    single<DiagnosticsPerformanceCapture> { get<AndroidDiagnosticsPerformanceRecorder>() }
    single { DiagnosticsFileLogger(androidContext().noBackupFilesDir) }
    single {
        BreadcrumbJournal(androidContext().noBackupFilesDir).also(SiloLog::installBreadcrumbSink)
    }

    single<DiagnosticsSavedServerProvider> { RegistryDiagnosticsSavedServerProvider(get()) }
    single<DiagnosticsStatusProvider> { ApiDiagnosticsStatusProvider(get()) }
    single<DiagnosticsAccountProvider> { RepositoryDiagnosticsAccountProvider(get()) }
    single<DiagnosticsProfileProvider> { RepositoryDiagnosticsProfileProvider(get()) }
    single<DiagnosticsIdentityResolver>(SELF_HOSTED_DIAGNOSTICS_IDENTITY) {
        DefaultDiagnosticsIdentityResolver(
            tokenManager = get(),
            identityTransitions = get(),
            savedServerProvider = get(),
            statusProvider = get(),
            accountProvider = get(),
            profileProvider = get(),
        )
    }
    single<HttpClient>(HOSTED_DIAGNOSTICS_HTTP) { createHostedDiagnosticsClient() }
    single<HostedDiagnosticsApi> { DefaultHostedDiagnosticsApi(get(HOSTED_DIAGNOSTICS_HTTP)) }
    single<HostedDiagnosticsCapabilitiesStore> {
        val settings = get<DiagnosticsSettingsStore>()
        object : HostedDiagnosticsCapabilitiesStore {
            override suspend fun load() = settings.hostedCapabilities()
            override suspend fun save(capabilities: org.siloserver.silo.network.api.HostedDiagnosticsCapabilities) {
                settings.cacheHostedCapabilities(capabilities)
            }
        }
    }
    single { HostedDiagnosticsCapabilitiesRepository(get(), get()) }
    single<HostedDiagnosticsBindingOwnerStore> {
        val settings = get<DiagnosticsSettingsStore>()
        object : HostedDiagnosticsBindingOwnerStore {
            override suspend fun load(localServerId: String) = settings.hostedBindingOwner(localServerId)
            override suspend fun save(localServerId: String, owner: String) {
                settings.cacheHostedBindingOwner(localServerId, owner)
            }
        }
    }
    single<HostedDiagnosticsCredentialStore> {
        EncryptedPreferencesHostedDiagnosticsCredentialStore(get())
    }
    single { HostedDiagnosticsInstallationManager(get(), get(), get()) }
    single<HostedDiagnosticsReportDeleter> { DefaultHostedDiagnosticsReportDeleter(get(), get()) }
    single<DiagnosticsIdentityResolver> {
        DestinationDiagnosticsIdentityResolver(
            destination = { get<DiagnosticsSettingsStore>().destinationKind() },
            hosted = HostedDiagnosticsIdentityResolver(
                tokenManager = get(),
                identityTransitions = get(),
                registry = get(),
                accountProvider = get(),
                profileProvider = get(),
                capabilities = get(),
                bindingOwners = get(),
            ),
            selfHosted = get(SELF_HOSTED_DIAGNOSTICS_IDENTITY),
        )
    }

    single<DiagnosticsBundleBuilder> { FileDiagnosticsBundleBuilder() }
    single<DiagnosticsRedactionTokenProvider> {
        val tokenManager = get<TokenManager>()
        val hostedInstallations = get<HostedDiagnosticsInstallationManager>()
        DestinationAwareDiagnosticsRedactionTokenProvider(tokenManager, get()) {
            hostedInstallations.credentialsForOutstanding().map { it.installationToken }
        }
    }
    single<DiagnosticsUploader> {
        val tokenManager = get<TokenManager>()
        val settings = get<DiagnosticsSettingsStore>()
        DefaultDiagnosticsUploader(
            reports = get(),
            identity = get(),
            identityTransitions = get(),
            privacyBarrier = get(),
            bundleBuilder = get(),
            api = get(),
            hostedApi = get(),
            hostedInstallations = get(),
            hostedCapabilities = get(),
            redactionTokens = get(),
            selfHostedAuthorization = DiagnosticsSelfHostedAuthorizationProvider {
                val scope = tokenManager.snapshotCurrentScope()
                    ?.takeIf { it.credentialGenerationId == null }
                    ?: return@DiagnosticsSelfHostedAuthorizationProvider null
                val accessToken = tokenManager.getAccessTokenForScope(scope)
                    ?.takeIf(String::isNotBlank)
                    ?: return@DiagnosticsSelfHostedAuthorizationProvider null
                DiagnosticsUploadAuthorization(
                    serverId = scope.serverId,
                    serverUrl = scope.serverUrl,
                    accessToken = accessToken,
                    activeProfileId = scope.profileId,
                    identityGeneration = scope.identityGeneration,
                )
            },
            sentRecorder = get(),
            consentProvider = get(),
            transportPolicy = DiagnosticsTransportPolicy { binding, noticeVersion, requireAlways ->
                settings.permitsUpload(binding, noticeVersion, requireAlways)
            },
            staleConsentHandler = get(),
        )
    }

    single<DiagnosticsDeviceProbe> { AndroidDiagnosticsDeviceProbe(androidContext(), get(), get()) }
    single { DeviceSnapshotCollector(get()) }
    single { DeviceSnapshotCache() }
    single { androidExitReportEnvironment(androidContext(), get(), get()) }
    single { FileJvmCrashMarkerSource(androidContext().noBackupFilesDir) }
    single<DiagnosticsStoredEvidenceReconciler> {
        val markers = get<FileJvmCrashMarkerSource>()
        DiagnosticsStoredEvidenceReconciler(markers::reconcile)
    }
    single<AndroidExitInfoSource> { FrameworkAndroidExitInfoSource(androidContext()) }
    single<ProcessStateSummaryPublisher> { AndroidProcessStateSummaryPublisher(androidContext()) }
    single { DiagnosticsRunLedger(androidContext().noBackupFilesDir, get()) }
    single<DiagnosticsCaptureController> {
        FileDiagnosticsCaptureController(
            logBuffer = get(),
            fileLogger = get(),
            breadcrumbJournal = get(),
            reports = get(),
            deviceSnapshots = get(),
            deviceSnapshotCache = get(),
            environment = get(),
            playbackSessions = get(),
            performanceCapture = get(),
        )
    }
    single<DiagnosticsRuntimePublisher> {
        DefaultDiagnosticsRuntimePublisher(
            captureSessionIdFactory = { SiloLog.captureSessionId },
            ledger = get(),
            logBuffer = get(),
            deviceSnapshots = get(),
            deviceSnapshotCache = get(),
            redactionTokens = get(),
            playbackSessions = get(),
        )
    }
    single<DiagnosticsIncidentCollector> {
        val source = get<AndroidExitInfoSource>()
        val ledger = get<DiagnosticsRunLedger>()
        val reports = get<PendingReportStore>()
        val markers = get<FileJvmCrashMarkerSource>()
        val environment = get<ExitReportEnvironment>()
        val cache = get<DeviceSnapshotCache>()
        val tokenProvider = get<DiagnosticsRedactionTokenProvider>()
        DiagnosticsIncidentCollector { context, consent ->
            // Exact credentials are part of the hosted redaction boundary. If
            // they cannot be read, leave the raw marker for a later refresh
            // instead of assembling evidence with a weaker token set.
            val tokens = tokenProvider.tokens(context.destinationKind)
            ExitInfoCollector(
                source = source,
                ledger = ledger,
                reports = reports,
                markers = markers,
                environment = environment,
                deviceSnapshotBytes = cache::currentBytes,
                noticeVersion = { context.noticeVersion },
                consentMode = {
                    when (consent) {
                        DiagnosticsConsentMode.ASK ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
                        DiagnosticsConsentMode.ALWAYS ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS
                        DiagnosticsConsentMode.NEVER ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
                    }
                },
                redactionTokens = { tokens },
                breadcrumbs = get<BreadcrumbJournal>(),
            ).collect()
        }
    }
    single<DiagnosticsBindingPurger> {
        val reports = get<PendingReportStore>()
        val capture = get<DiagnosticsCaptureController>()
        val ledger = get<DiagnosticsRunLedger>()
        val markers = get<FileJvmCrashMarkerSource>()
        DiagnosticsBindingPurger { binding, includeLiveCapture ->
            var failure: Throwable? = null
            suspend fun attempt(block: suspend () -> Unit) {
                runCatching { block() }.onFailure { error -> if (failure == null) failure = error }
            }
            if (includeLiveCapture) attempt { capture.purgeCurrentEvidence() }
            attempt { reports.purge(binding) }
            attempt { ledger.purge(binding) }
            attempt { markers.purge(binding) }
            failure?.let { throw it }
        }
    }
    single<DiagnosticsAllEvidencePurger> {
        val reports = get<PendingReportStore>()
        val capture = get<DiagnosticsCaptureController>()
        val ledger = get<DiagnosticsRunLedger>()
        val markers = get<FileJvmCrashMarkerSource>()
        DiagnosticsAllEvidencePurger { includeLiveCapture ->
            var failure: Throwable? = null
            suspend fun attempt(block: suspend () -> Unit) {
                runCatching { block() }.onFailure { error -> if (failure == null) failure = error }
            }
            if (includeLiveCapture) attempt { capture.purgeCurrentEvidence() }
            attempt { reports.purgeAll() }
            attempt { ledger.clear() }
            attempt { markers.purgeAll() }
            failure?.let { throw it }
        }
    }
    single {
        DiagnosticsSettingsStore(
            dataStore = get(DIAGNOSTICS_DATA_STORE),
            bindingPurger = get(),
            allEvidencePurger = get(),
        )
    }
    single<DiagnosticsUploadConsentProvider> {
        val settings = get<DiagnosticsSettingsStore>()
        DiagnosticsUploadConsentProvider { binding, noticeVersion ->
            settings.consent(binding, noticeVersion).mode
        }
    }
    single<DiagnosticsSentRecorder> {
        val settings = get<DiagnosticsSettingsStore>()
        DiagnosticsSentRecorder { binding, shortId, sentAtEpochMs, state ->
            settings.recordSent(binding, shortId, sentAtEpochMs, state)
        }
    }
    single<DiagnosticsStaleConsentHandler> {
        SettingsDiagnosticsStaleConsentHandler(get())
    }
    single<DiagnosticsUploadScheduler> {
        val context = androidContext()
        DiagnosticsUploadScheduler { reportId -> DiagnosticsUploadWorker.enqueue(context, reportId) }
    }
    single<HostedDiagnosticsDeletionScheduler> {
        val context = androidContext()
        HostedDiagnosticsDeletionScheduler { HostedDiagnosticsDeletionWorker.enqueue(context) }
    }
    single<CoroutineScope>(DIAGNOSTICS_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<DiagnosticsCoordinator> {
        DefaultDiagnosticsCoordinator(
            scope = get(DIAGNOSTICS_SCOPE),
            identity = get(),
            identityTransitions = get(),
            privacyBarrier = get(),
            settings = get(),
            reports = get(),
            capture = get(),
            uploader = get(),
            uploadScheduler = get(),
            hostedDeletionScheduler = get(),
            hostedReportDeleter = get(),
            runtimePublisher = get(),
            incidentCollector = get(),
            storedEvidenceReconciler = get(),
        )
    }
}

@Suppress("DEPRECATION")
private fun androidExitReportEnvironment(
    context: android.content.Context,
    probe: DiagnosticsDeviceProbe,
    buildIdentity: SiloClientBuildIdentity,
): ExitReportEnvironment {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val identity = probe.identity()
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val platform = if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
        DiagnosticsPlatform.ANDROID_TV
    } else {
        DiagnosticsPlatform.ANDROID
    }
    // CI's build counter, the same value the X-Silo-Client-Build header and the
    // v3 playback context carry, so a crash report and an Activity session for
    // one install agree on which build they came from. This used to be the
    // versionCode, which is the form-factor-doubled release code and therefore
    // a different number entirely. silo-apple reports CFBundleVersion on all
    // three carriers for the same reason. The manifest requires a non-empty
    // string (DiagnosticsValidation), so an unstamped local build keeps the
    // literal "0" here rather than collapsing to absent.
    val build = buildIdentity.buildNumber
    return ExitReportEnvironment(
        appVersion = packageInfo.versionName ?: "unknown",
        appBuild = build,
        platform = platform,
        osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" },
        deviceSummary = DiagnosticsDeviceSummary(
            manufacturer = identity.manufacturer,
            model = identity.model,
            os = "Android ${identity.osRelease}",
            formFactor = identity.formFactor,
        ),
    )
}
