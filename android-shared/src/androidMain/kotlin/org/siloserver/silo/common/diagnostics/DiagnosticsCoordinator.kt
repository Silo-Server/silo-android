package org.siloserver.silo.common.diagnostics

import android.util.Log
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.diagnostics.bundle.DiagnosticsBundleBuilder
import org.siloserver.silo.common.diagnostics.consent.ConsentChoice
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsBinding
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsConsentStore
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsProfileGate
import org.siloserver.silo.common.diagnostics.consent.DiagnosticsSettingsStore
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.common.diagnostics.consent.RecentSessionTracker
import org.siloserver.silo.common.diagnostics.consent.SentReport
import org.siloserver.silo.common.diagnostics.consent.toManifestMode
import org.siloserver.silo.common.diagnostics.crash.CrashMarkerAssembler
import org.siloserver.silo.common.diagnostics.crash.ExitInfoCollector
import org.siloserver.silo.common.diagnostics.logging.BreadcrumbJournal
import org.siloserver.silo.common.diagnostics.logging.DiagRedactor
import org.siloserver.silo.common.diagnostics.logging.DiagnosticsFileLogger
import org.siloserver.silo.common.diagnostics.logging.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailability
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.model.feature.DiagnosticsFeatureStore
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DiagnosticsRepository
import java.net.URI

/**
 * Central diagnostics orchestrator shared by the phone and TV apps.
 *
 * Owns: binding resolution (server_instance_id + account, offline-capable via
 * the cached status), the crash-context published for the UEH marker, consent
 * transitions (incl. the Never purge cascade), prompt eligibility, the manual
 * capture flow, and upload orchestration with the server's error vocabulary
 * (including persisted Retry-After backoff — a deliberate improvement over the
 * Apple client, which ignores the header).
 *
 * Threading: one supervisor IO scope, mirroring PlaybackSessionManager. UI
 * calls suspend functions from viewModelScope; fire-and-forget entry points
 * (launch/foreground) launch into [scope].
 */
class DiagnosticsCoordinator(
    private val platform: String,
    private val appVersion: String,
    private val appBuild: String,
    private val repository: DiagnosticsRepository,
    private val featureStore: DiagnosticsFeatureStore,
    private val consentStore: DiagnosticsConsentStore,
    private val pendingReportStore: PendingReportStore,
    private val settingsStore: DiagnosticsSettingsStore,
    private val sessionTracker: RecentSessionTracker,
    private val deviceSnapshotCollector: DeviceSnapshotCollector,
    private val bundleBuilder: DiagnosticsBundleBuilder,
    private val fileLogger: DiagnosticsFileLogger,
    private val breadcrumbJournal: BreadcrumbJournal,
    private val markerAssembler: CrashMarkerAssembler,
    private val exitInfoCollector: ExitInfoCollector,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val profileGate: DiagnosticsProfileGate,
    private val activeProfileProvider: suspend () -> ActiveProfileInfo?,
) {
    /** Minimal profile view the coordinator needs (id + child flag). */
    data class ActiveProfileInfo(val id: String, val isChild: Boolean)

    data class State(
        val availability: DiagnosticsAvailability = DiagnosticsAvailability.UNKNOWN,
        val status: DiagnosticsStatusResponse? = null,
        val binding: DiagnosticsBinding? = null,
        val serverName: String? = null,
        val consentChoice: ConsentChoice = ConsentChoice.ASK,
        val pendingReports: List<PendingReportStore.PendingReport> = emptyList(),
        val sentHistory: List<SentReport> = emptyList(),
        val debugLogging: Boolean = false,
        val captureActive: Boolean = false,
        val canManage: Boolean = false,
        val isUploading: Boolean = false,
    )

    data class Prompt(
        val binding: DiagnosticsBinding,
        val reports: List<PendingReportStore.PendingReport>,
    )

    sealed class UploadOutcome {
        data class Uploaded(val shortId: String) : UploadOutcome()
        data class Kept(val userMessage: String, val permanent: Boolean) : UploadOutcome()
        data object Discarded : UploadOutcome()
        data object Skipped : UploadOutcome()
    }

    data class ManualReview(
        val report: PendingReportStore.PendingReport,
        val lineCount: Int,
        val categories: List<String>,
        val approxLogBytes: Long,
        val ringOnly: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val uploadMutex = Mutex()
    private val inFlightReportIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    init {
        consentStore.onNeverSelected = { binding -> onNeverSelected(binding) }
    }

    @Volatile
    private var captureActive = false

    @Volatile
    private var lastForegroundRefreshMs = 0L

    /** Last active server whose account had a token — for sign-out detection. */
    @Volatile
    private var lastObservedServerId: String? = null

    // ---- lifecycle entry points -----------------------------------------

    /** Application.onCreate (post-Koin). Never load-bearing for cold start. */
    fun onAppLaunched() {
        scope.launch {
            runCatching { deviceSnapshotCollector.warmUpPreFailureSnapshot() }
                .onFailure { Log.w(TAG, "device snapshot warm-up failed", it) }
            runCatching { refreshBindingAndState() }
                .onFailure { Log.w(TAG, "diagnostics launch refresh failed", it) }
            // Marker assembly + exit-info collection need the binding published
            // first only for *new* context; markers carry their own binding.
            runCatching { markerAssembler.assemblePendingMarkers() }
                .onFailure { Log.w(TAG, "crash marker assembly failed", it) }
            CrashContextCache.session?.let { session ->
                runCatching { exitInfoCollector.collectOnLaunch(session) }
                    .onFailure { Log.w(TAG, "exit-info collection failed", it) }
            }
            rebuildState()
            evaluatePromptAndAutoUploads()
        }
    }

    /** App came to foreground; throttled internally. */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundRefreshMs < FOREGROUND_REFRESH_MIN_INTERVAL_MS) return
        lastForegroundRefreshMs = now
        scope.launch {
            runCatching {
                refreshBindingAndState()
                evaluatePromptAndAutoUploads()
            }.onFailure { Log.w(TAG, "diagnostics foreground refresh failed", it) }
        }
    }

    /** Settings screen entry / pull-to-refresh. */
    suspend fun refreshNow() {
        refreshBindingAndState()
        evaluatePromptAndAutoUploads()
    }

    /** Profile switched (or is switching): disarm immediately, re-resolve. */
    fun onProfileChanged() {
        profileGate.invalidate()
        scope.launch {
            runCatching { refreshBindingAndState() }
            evaluatePromptAndAutoUploads()
        }
    }

    /** Sign-out of the active server: purge that binding's diagnostics state. */
    fun onSignOut(localServerId: String?) {
        profileGate.invalidate()
        CrashContextCache.clearSession()
        SiloLog.resetRing()
        breadcrumbJournal.setEnabled(false)
        scope.launch {
            localServerId?.let { purgeLocalServer(it) }
            rebuildState()
            _prompt.value = null
        }
    }

    private fun purgeLocalServer(localServerId: String) {
        consentStore.serverInstanceForLocalId(localServerId)?.let(::purgeServerInstance)
        // Segment/breadcrumb files are device-wide but carry the departing
        // account's context — the consent contract purges them on sign-out and
        // server removal, trading a little evidence for zero leakage.
        fileLogger.purge()
        breadcrumbJournal.purge()
        _prompt.value = null
    }

    private fun purgeServerInstance(instanceId: String) {
        pendingReportStore.purgeServer(instanceId)
        consentStore.purgeServer(instanceId)
        sessionTracker.purgeServer(instanceId)
    }

    /** Server removed from the registry entirely. */
    fun onServerRemoved(localServerId: String) {
        scope.launch {
            consentStore.forgetLocalServer(localServerId)?.let(::purgeServerInstance)
            fileLogger.purge()
            breadcrumbJournal.purge()
            rebuildState()
        }
    }

    // ---- consent + settings ---------------------------------------------

    suspend fun setConsentChoice(choice: ConsentChoice) = withContext(Dispatchers.IO) {
        val binding = _state.value.binding ?: return@withContext
        if (!profileGate.isEligibleNow()) return@withContext
        val noticeVersion = _state.value.status?.consentNoticeVersion
            ?: consentStore.storedRecord(binding)?.noticeVersion ?: 1
        consentStore.setMode(binding, choice, noticeVersion)
        breadcrumbJournal.setEnabled(choice != ConsentChoice.NEVER)
        publishCrashContext()
        rebuildState()
        if (choice == ConsentChoice.ALWAYS) evaluatePromptAndAutoUploads() else _prompt.value = null
    }

    suspend fun setDebugLogging(enabled: Boolean) = withContext(Dispatchers.IO) {
        settingsStore.setDebugLogging(enabled)
        applyDebugLogging(enabled || captureActive)
        rebuildState()
    }

    // ---- manual capture --------------------------------------------------

    /** Start diagnostic capture: verbose logging on (independent of the persistent toggle). */
    fun startManualCapture() {
        if (!profileGate.isEligibleNow()) return
        captureActive = true
        applyDebugLogging(true)
        SiloLog.i(DiagnosticsLogCategory.LIFECYCLE, "Diagnostics", "manual diagnostic capture started")
        rebuildState()
    }

    /** Stop & review: freezes a manual report from current evidence. */
    suspend fun stopManualCaptureAndBuildReview(): ManualReview? {
        captureActive = false
        applyDebugLogging(settingsStore.debugLogging.value)
        val review = buildManualReport()
        rebuildState()
        return review
    }

    /** One-shot "Send Diagnostics Now" evidence (ring-only warning applies when debug logging is off). */
    suspend fun buildManualReport(): ManualReview? = withContext(Dispatchers.IO) {
        buildManualReportBlocking()
    }

    private suspend fun buildManualReportBlocking(): ManualReview? {
        val current = _state.value
        val binding = current.binding ?: return null
        if (!profileGate.isEligibleNow()) return null

        val segments = fileLogger.segmentFiles()
        val ringOnly = segments.isEmpty()
        val logLines: List<String> = if (ringOnly) {
            SiloLog.ringSnapshot().lines.map { it.decodeToString() }
        } else {
            segments.flatMap { file ->
                runCatching { file.readText().lineSequence().filter { it.isNotBlank() }.toList() }
                    .getOrDefault(emptyList())
            }
        }
        val logsJsonl = logLines.joinToString("\n")
        val droppedLines = SiloLog.ringSnapshot().droppedCount + fileLogger.droppedLines()

        val profile = activeProfileProvider()
        val profileId = profile?.takeIf { !it.isChild }?.id
        val nowIso = Instant.now().toString()
        val draft = DiagnosticsManifestDraft(
            report = DiagnosticsManifest.Report(
                type = DiagnosticsReportType.MANUAL,
                capturedAt = nowIso,
                captureSessionId = SiloLog.captureSessionId,
                appVersion = appVersion.ifBlank { "unknown" },
                appBuild = appBuild.ifBlank { "unknown" },
                platform = if (platform == "android-tv") DiagnosticsPlatform.ANDROID_TV else DiagnosticsPlatform.ANDROID,
                osVersion = deviceSnapshotCollector.osVersion(),
                profileId = profileId,
            ),
            destination = DiagnosticsManifest.Destination(binding.serverInstanceId),
            consent = DiagnosticsManifest.Consent(
                mode = DiagnosticsConsentMode.MANUAL,
                noticeVersion = current.status?.consentNoticeVersion
                    ?: consentStore.storedRecord(binding)?.noticeVersion ?: 1,
            ),
            crash = null,
            deviceSummary = deviceSnapshotCollector.deviceSummary(),
            playbackSessionIds = sessionTracker.recent(binding).takeLast(20),
            logSummary = DiagnosticsManifest.LogSummary(
                lines = logLines.size.toLong(),
                bytesGz = 0,
                droppedLines = droppedLines,
                categories = emptyList(),
                debugLogging = !ringOnly,
            ),
        )
        val deviceJson = encodeDeviceSnapshot(DiagnosticsDeviceProvenance.PRE_FAILURE)

        val saved = pendingReportStore.save(
            PendingReportStore.PendingReportCapture(
                binding = binding,
                profileId = profileId,
                capturedAtEpochMs = System.currentTimeMillis(),
                type = DiagnosticsReportType.MANUAL,
                fingerprint = "manual|${System.currentTimeMillis()}",
                manifestDraft = draft,
                artifacts = buildList {
                    add(PendingReportStore.Artifact("device.json", deviceJson))
                    if (logsJsonl.isNotEmpty()) {
                        add(PendingReportStore.Artifact("logs.jsonl", logsJsonl.encodeToByteArray()))
                    }
                },
            ),
        ) ?: return null
        rebuildState()

        val categories = logLines.mapNotNullTo(LinkedHashSet()) { line ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(line)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("cat")?.let { c -> (c as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }.getOrNull()
        }
        return ManualReview(
            report = saved,
            lineCount = logLines.size,
            categories = categories.toList(),
            approxLogBytes = logsJsonl.length.toLong(),
            ringOnly = ringOnly,
        )
    }

    // ---- prompt actions --------------------------------------------------

    suspend fun acceptPrompt(always: Boolean): List<UploadOutcome> = withContext(Dispatchers.IO) {
        acceptPromptBlocking(always)
    }

    private suspend fun acceptPromptBlocking(always: Boolean): List<UploadOutcome> {
        val prompt = _prompt.value ?: return emptyList()
        _prompt.value = null
        if (always) {
            val noticeVersion = _state.value.status?.consentNoticeVersion ?: 1
            consentStore.setMode(prompt.binding, ConsentChoice.ALWAYS, noticeVersion)
            publishCrashContext()
        }
        val outcomes = prompt.reports.map { uploadPending(it) }
        rebuildState()
        return outcomes
    }

    suspend fun declinePrompt() = withContext(Dispatchers.IO) {
        val prompt = _prompt.value ?: return@withContext
        _prompt.value = null
        prompt.reports.forEach { pendingReportStore.markPromptDeclined(it) }
        rebuildState()
    }

    suspend fun deletePending(report: PendingReportStore.PendingReport) = withContext(Dispatchers.IO) {
        pendingReportStore.delete(report)
        rebuildState()
    }

    // ---- upload ----------------------------------------------------------

    suspend fun uploadPending(
        report: PendingReportStore.PendingReport,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        if (!inFlightReportIds.add(report.id)) return@withContext UploadOutcome.Skipped
        try {
            _state.value = _state.value.copy(isUploading = true)
            doUpload(report)
        } finally {
            inFlightReportIds.remove(report.id)
            _state.value = _state.value.copy(isUploading = false)
            rebuildState()
        }
    }

    private suspend fun doUpload(report: PendingReportStore.PendingReport): UploadOutcome {
        val current = _state.value
        val binding = current.binding ?: return UploadOutcome.Skipped
        if (report.binding.binding != binding) {
            return UploadOutcome.Kept("This report belongs to a different server account.", permanent = false)
        }
        if (current.availability != DiagnosticsAvailability.AVAILABLE) {
            return UploadOutcome.Kept(availabilityMessage(current.availability), permanent = false)
        }
        if (!profileGate.isEligibleNow()) return UploadOutcome.Skipped

        // Server-directed backoff: no network call before the deadline.
        pendingReportStore.retryAfterDeadlineEpochMs(binding)?.let {
            return UploadOutcome.Kept("The server asked us to retry later.", permanent = false)
        }

        // Refresh consent framing at upload time (evidence is frozen; consent is not).
        val noticeVersion = current.status?.consentNoticeVersion ?: 1
        val consent = consentStore.record(binding, noticeVersion)
        val isManual = report.manifestDraft.report.type == DiagnosticsReportType.MANUAL
        val refreshed = pendingReportStore.updatingConsent(
            report,
            if (isManual) DiagnosticsConsentMode.MANUAL else consent.mode.toManifestMode(),
            noticeVersion,
        )

        val identityBefore = identitySnapshot()
        val built = try {
            bundleBuilder.build(
                DiagnosticsBundleBuilder.Input(
                    draft = refreshed.manifestDraft,
                    deviceJson = pendingReportStore.readArtifact(refreshed, "device.json")
                        ?: encodeDeviceSnapshot(DiagnosticsDeviceProvenance.POST_RESTART),
                    logsJsonl = pendingReportStore.readArtifact(refreshed, "logs.jsonl") ?: ByteArray(0),
                    stackTxt = pendingReportStore.readArtifact(refreshed, "crash/stack.txt"),
                    tombstonePb = pendingReportStore.readArtifact(refreshed, "crash/tombstone.pb"),
                    breadcrumbsJsonl = pendingReportStore.readArtifact(refreshed, "breadcrumbs.jsonl"),
                    debugLogging = refreshed.manifestDraft.logSummary.debugLogging,
                ),
                redactionTokens = currentRedactionTokens(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "bundle build failed; discarding report ${refreshed.id}", t)
            pendingReportStore.delete(refreshed)
            return UploadOutcome.Discarded
        }

        // The build (gzip + scrub) is slow enough for identity to change
        // underneath it; never post a bundle to a switched account/profile.
        val identityAfter = identitySnapshot()
        if (!identityAfter.hasAccessToken ||
            identityAfter.localServerId != identityBefore.localServerId ||
            identityAfter.profileId != identityBefore.profileId
        ) {
            return UploadOutcome.Kept("Sign-in changed during upload; kept for later.", permanent = false)
        }

        return when (val result = repository.upload(built.manifestJson, built.bundleBytes)) {
            is DiagnosticsUploadResult.Success -> {
                pendingReportStore.delete(refreshed)
                consentStore.recordSent(binding, result.result.shortId)
                pendingReportStore.clearRetryAfterDeadline(binding)
                UploadOutcome.Uploaded(result.result.shortId)
            }
            is DiagnosticsUploadResult.NetworkError ->
                UploadOutcome.Kept("Network error; the report is kept for later.", permanent = false)
            is DiagnosticsUploadResult.Failure -> handleFailure(result, refreshed, binding)
        }
    }

    private fun handleFailure(
        failure: DiagnosticsUploadResult.Failure,
        report: PendingReportStore.PendingReport,
        binding: DiagnosticsBinding,
    ): UploadOutcome = when (failure.code) {
        DiagnosticsErrorCode.UNSUPPORTED_SCHEMA -> {
            pendingReportStore.markNeedsServerUpdate(report)
            UploadOutcome.Kept("Your server needs an update to accept diagnostics from this app.", permanent = true)
        }
        DiagnosticsErrorCode.TOO_LARGE -> {
            pendingReportStore.markTooLarge(report)
            UploadOutcome.Kept("This report is larger than the server accepts.", permanent = true)
        }
        DiagnosticsErrorCode.INVALID_BUNDLE,
        DiagnosticsErrorCode.ARCHIVE_MISMATCH,
        -> {
            // A client-side bundle bug; retrying forever is pointless.
            pendingReportStore.delete(report)
            UploadOutcome.Discarded
        }
        DiagnosticsErrorCode.STALE_CONSENT -> {
            val noticeVersion = _state.value.status?.consentNoticeVersion ?: report.manifestDraft.consent.noticeVersion
            consentStore.setMode(binding, ConsentChoice.ASK, noticeVersion)
            UploadOutcome.Kept("The server's consent notice changed; please review and send again.", permanent = false)
        }
        DiagnosticsErrorCode.QUOTA_EXCEEDED,
        DiagnosticsErrorCode.BUSY,
        -> {
            val seconds = failure.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS
            pendingReportStore.setRetryAfterDeadline(binding, System.currentTimeMillis() + seconds * 1000)
            UploadOutcome.Kept("The server is busy; will retry later.", permanent = false)
        }
        DiagnosticsErrorCode.DISABLED ->
            UploadOutcome.Kept("Diagnostics uploads are disabled on the server.", permanent = false)
        DiagnosticsErrorCode.STORAGE_UNAVAILABLE ->
            UploadOutcome.Kept("The server's diagnostics storage is unavailable.", permanent = false)
        DiagnosticsErrorCode.DESTINATION_MISMATCH,
        DiagnosticsErrorCode.PROFILE_MISMATCH,
        DiagnosticsErrorCode.CHILD_PROFILE_FORBIDDEN,
        ->
            UploadOutcome.Kept("This report can't be sent from the current sign-in.", permanent = false)
        else ->
            UploadOutcome.Kept("Upload failed (${failure.code.wire}); the report is kept.", permanent = false)
    }

    // ---- binding + state internals ---------------------------------------

    private suspend fun refreshBindingAndState() = refreshMutex.withLock {
        val localServerId = tokenManager.getCurrentServerId()
        val hasToken = tokenManager.getAccessToken() != null

        // Sign-out / server-removal detection: when the previously observed
        // server's account no longer has a token at all (not merely a server
        // switch), the consent contract requires its pending diagnostics state
        // to be deleted. This catch-all covers every sign-out path without
        // per-call-site wiring.
        val previous = lastObservedServerId
        if (previous != null && (previous != localServerId || !hasToken)) {
            val previousToken = runCatching { tokenManager.getAccessTokenForScope(previous) }.getOrNull()
            if (previousToken == null) purgeLocalServer(previous)
        }
        lastObservedServerId = if (hasToken) localServerId else null

        if (localServerId == null || !hasToken) {
            CrashContextCache.clearSession()
            breadcrumbJournal.setEnabled(false)
            applyDebugLogging(false)
            _state.value = State(debugLogging = settingsStore.debugLogging.value)
            return@withLock
        }

        featureStore.refresh()
        val featureState = featureStore.state.value
        var status = featureState.response
        var availability = featureState.availability
        var accountUserId: String? = null

        if (status != null && availability != DiagnosticsAvailability.OFFLINE) {
            when (val me = authRepository.getCurrentUser()) {
                is ApiResult.Success -> {
                    accountUserId = me.data.id.toString()
                    consentStore.rememberServerInstance(localServerId, status.serverInstanceId)
                    consentStore.cacheStatus(localServerId, status, accountUserId)
                }
                is ApiResult.Error, is ApiResult.NetworkError -> Unit
            }
        }
        if (accountUserId == null) {
            // Offline (or /me failed): fall back to the last-known snapshot so
            // crash capture can still bind. Transient failures only — a signed-
            // out account never reaches here (token check above).
            val cached = consentStore.cachedStatus(localServerId)
            if (cached != null) {
                status = status ?: cached.status
                if (availability == DiagnosticsAvailability.UNKNOWN) {
                    availability = DiagnosticsAvailability.OFFLINE
                }
                accountUserId = cached.accountUserId
            }
        }

        val binding = if (status != null && accountUserId != null) {
            DiagnosticsBinding(status.serverInstanceId, accountUserId)
        } else {
            null
        }

        // Redact the server host wherever it appears in free text.
        val serverHost = runCatching { URI(tokenManager.getServerUrl()).host }.getOrNull()
        serverHost?.let { DiagRedactor.registerSensitiveHost(it) }

        val profile = runCatching { activeProfileProvider() }.getOrNull()
        profileGate.reevaluate(profile?.id, noProfileIsEligible = false)

        sessionTracker.currentBindingProvider = { binding }
        sessionTracker.publishToCrashContext(binding)

        val choice = binding?.let { consentStore.record(it, status?.consentNoticeVersion ?: 1).mode }
            ?: ConsentChoice.ASK
        breadcrumbJournal.setEnabled(binding != null && choice != ConsentChoice.NEVER)
        applyDebugLogging(settingsStore.debugLogging.value || captureActive)

        publishCrashContext(binding, status, profile)
        _state.value = _state.value.copy(serverName = serverHost)
        rebuildState(binding, availability, status)
    }

    private suspend fun publishCrashContext(
        binding: DiagnosticsBinding? = _state.value.binding,
        status: DiagnosticsStatusResponse? = _state.value.status,
        profile: ActiveProfileInfo? = null,
    ) {
        if (binding == null) {
            CrashContextCache.clearSession()
            return
        }
        val resolvedProfile = profile ?: runCatching { activeProfileProvider() }.getOrNull()
        val choice = consentStore.storedRecord(binding)?.mode ?: ConsentChoice.ASK
        CrashContextCache.session = CrashSessionContext(
            serverInstanceId = binding.serverInstanceId,
            accountUserId = binding.accountUserId,
            // Attribution only for confirmed non-child profiles; the server
            // rejects child-profile attribution outright.
            profileId = resolvedProfile?.takeIf { !it.isChild }?.id,
            consentMode = when (choice) {
                ConsentChoice.ALWAYS -> "always"
                ConsentChoice.NEVER -> "never"
                ConsentChoice.ASK -> "ask"
            },
            noticeVersion = status?.consentNoticeVersion
                ?: consentStore.storedRecord(binding)?.noticeVersion ?: 1,
            appVersion = appVersion,
            appBuild = appBuild,
            platform = platform,
            osVersion = deviceSnapshotCollector.osVersion(),
        )
    }

    private fun rebuildState(
        binding: DiagnosticsBinding? = _state.value.binding,
        availability: DiagnosticsAvailability = _state.value.availability,
        status: DiagnosticsStatusResponse? = _state.value.status,
    ) {
        val pending = binding?.let { pendingReportStore.listReports(it) } ?: emptyList()
        val choice = binding?.let { consentStore.storedRecord(it)?.mode } ?: ConsentChoice.ASK
        _state.value = State(
            availability = availability,
            status = status,
            binding = binding,
            serverName = _state.value.serverName,
            consentChoice = choice,
            pendingReports = pending,
            sentHistory = binding?.let { consentStore.sentHistory(it) } ?: emptyList(),
            debugLogging = settingsStore.debugLogging.value,
            captureActive = captureActive,
            canManage = profileGate.isEligibleNow(),
            isUploading = _state.value.isUploading,
        )
    }

    private suspend fun evaluatePromptAndAutoUploads() {
        val current = _state.value
        val binding = current.binding
        if (binding == null) {
            _prompt.value = null
            return
        }
        if (current.availability != DiagnosticsAvailability.AVAILABLE || !profileGate.isEligibleNow()) {
            _prompt.value = null
            return
        }
        val crashReports = pendingReportStore.listReports(binding)
            .filter { it.manifestDraft.report.type != DiagnosticsReportType.MANUAL }
            .filterNot { it.state.isPermanentFailure || it.state.promptDeclined }
        when (consentStore.record(binding, current.status?.consentNoticeVersion ?: 1).mode) {
            ConsentChoice.ASK -> {
                _prompt.value = if (crashReports.isEmpty()) null else Prompt(binding, crashReports)
            }
            ConsentChoice.ALWAYS -> {
                _prompt.value = null
                uploadMutex.withLock {
                    for (report in crashReports) {
                        val fingerprint = report.binding.fingerprint
                        if (!pendingReportStore.canAutoUpload(binding, fingerprint)) continue
                        pendingReportStore.recordAutoUploadAttempt(binding, fingerprint)
                        uploadPending(report)
                    }
                }
            }
            ConsentChoice.NEVER -> _prompt.value = null
        }
    }

    private fun applyDebugLogging(enabled: Boolean) {
        if (enabled) {
            fileLogger.start()
            SiloLog.installFileLogger(fileLogger)
        } else {
            SiloLog.installFileLogger(null)
            fileLogger.stop()
        }
    }

    private suspend fun identitySnapshot(): IdentitySnapshot = IdentitySnapshot(
        localServerId = tokenManager.getCurrentServerId(),
        profileId = tokenManager.getProfileId(),
        hasAccessToken = tokenManager.getAccessToken() != null,
    )

    private data class IdentitySnapshot(
        val localServerId: String?,
        val profileId: String?,
        val hasAccessToken: Boolean,
    )

    private suspend fun currentRedactionTokens(): List<String> = listOfNotNull(
        tokenManager.getAccessToken(),
        tokenManager.getProfileToken(),
        tokenManager.getRefreshToken(),
    )

    private fun encodeDeviceSnapshot(provenance: DiagnosticsDeviceProvenance): ByteArray {
        val snapshot = deviceSnapshotCollector.collect(provenance, deviceId = null)
        return SiloJson.encodeToString(DiagnosticsDeviceSnapshot.serializer(), snapshot).encodeToByteArray()
    }

    private fun availabilityMessage(availability: DiagnosticsAvailability): String = when (availability) {
        DiagnosticsAvailability.DISABLED -> "Diagnostics uploads are disabled on the server."
        DiagnosticsAvailability.STORAGE_UNAVAILABLE -> "The server's diagnostics storage is unavailable."
        DiagnosticsAvailability.OFFLINE -> "The server can't be reached right now."
        else -> "Diagnostics uploads aren't available right now."
    }

    /** The Never purge cascade, wired by DI into [DiagnosticsConsentStore.onNeverSelected]. */
    fun onNeverSelected(binding: DiagnosticsBinding) {
        pendingReportStore.purge(binding)
        sessionTracker.purge(binding)
        breadcrumbJournal.purge()
        fileLogger.purge()
        SiloLog.resetRing()
        // Deliberately NOT cleared: the global seen-fingerprint set (the crash
        // physically happened; flipping Never→Ask later must not resurrect
        // already-discarded reports).
        _prompt.value = null
    }

    private companion object {
        const val TAG = "DiagnosticsCoordinator"
        const val FOREGROUND_REFRESH_MIN_INTERVAL_MS = 30_000L
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}
