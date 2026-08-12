package org.siloserver.silo.common.diagnostics

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.api.HostedDiagnosticsReportState

data class ActiveDiagnosticsCapture(
    val generation: Long,
    val identityKey: DiagnosticsIdentityKey,
    val startedAtEpochMs: Long,
)

interface DiagnosticsCaptureController {
    /** Synchronous privacy boundary called before an identity mutation becomes visible. */
    fun closeGate()

    suspend fun start(context: DiagnosticsCaptureContext): ActiveDiagnosticsCapture
    suspend fun stop(active: ActiveDiagnosticsCapture, context: DiagnosticsCaptureContext): PendingReport?
    suspend fun cancel(active: ActiveDiagnosticsCapture)
    suspend fun captureNow(context: DiagnosticsCaptureContext): PendingReport?
    suspend fun setDebugLogging(context: DiagnosticsCaptureContext?, enabled: Boolean) = Unit
    suspend fun setPersistentBreadcrumbs(context: DiagnosticsCaptureContext?, enabled: Boolean) = Unit
    /** Removes detached crash-interrupted evidence before capture state is exposed or enabled. */
    suspend fun reconcileStoredEvidence() = Unit
    /** Removes all live/global evidence after [closeGate] has stopped new writes. */
    suspend fun purgeCurrentEvidence()
}

fun interface DiagnosticsUploadScheduler {
    fun enqueue(reportId: String)
}

fun interface HostedDiagnosticsDeletionScheduler {
    fun enqueue()

    data object None : HostedDiagnosticsDeletionScheduler {
        override fun enqueue() = Unit
    }
}

interface DiagnosticsRuntimePublisher {
    fun closeGate()
    suspend fun publish(context: DiagnosticsCaptureContext)

    data object None : DiagnosticsRuntimePublisher {
        override fun closeGate() = Unit
        override suspend fun publish(context: DiagnosticsCaptureContext) = Unit
    }
}

fun interface DiagnosticsIncidentCollector {
    suspend fun collect(
        context: DiagnosticsCaptureContext,
        consent: DiagnosticsConsentMode,
    ): List<PendingReport>
}

fun interface DiagnosticsStoredEvidenceReconciler {
    fun reconcile()

    data object None : DiagnosticsStoredEvidenceReconciler {
        override fun reconcile() = Unit
    }
}

interface DiagnosticsCoordinator {
    val state: StateFlow<DiagnosticsUiState>

    fun start()
    suspend fun refresh()
    suspend fun setConsent(mode: DiagnosticsConsentMode, expectedNoticeVersion: Int? = null)
    suspend fun setDestination(destinationKind: DiagnosticsDestinationKind)
    suspend fun setDebugLogging(enabled: Boolean)
    suspend fun captureNow(): String?
    suspend fun startTimedCapture()
    suspend fun stopTimedCapture(): String?
    suspend fun cancelTimedCapture()
    suspend fun upload(reportId: String, expectedNoticeVersion: Int? = null): DiagnosticsUploadDecision
    suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision = upload(reportId)
    suspend fun delete(reportId: String): Boolean
    suspend fun decline(reportId: String)
}

class DefaultDiagnosticsCoordinator(
    private val scope: CoroutineScope,
    private val identity: DiagnosticsIdentityResolver,
    private val identityTransitions: IdentityTransitionBarrier,
    private val privacyBarrier: DiagnosticsPrivacyBarrier = DiagnosticsPrivacyBarrier(),
    private val settings: DiagnosticsSettingsStore,
    private val reports: PendingReportStore,
    private val capture: DiagnosticsCaptureController,
    private val uploader: DiagnosticsUploader,
    private val uploadScheduler: DiagnosticsUploadScheduler,
    private val hostedDeletionScheduler: HostedDiagnosticsDeletionScheduler = HostedDiagnosticsDeletionScheduler.None,
    private val hostedReportDeleter: HostedDiagnosticsReportDeleter = HostedDiagnosticsReportDeleter.None,
    private val runtimePublisher: DiagnosticsRuntimePublisher = DiagnosticsRuntimePublisher.None,
    private val incidentCollector: DiagnosticsIncidentCollector = DiagnosticsIncidentCollector { _, _ -> emptyList() },
    private val storedEvidenceReconciler: DiagnosticsStoredEvidenceReconciler = DiagnosticsStoredEvidenceReconciler.None,
    actorDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DiagnosticsCoordinator {
    private val started = AtomicBoolean(false)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    private val actorScope = CoroutineScope(scope.coroutineContext + actorDispatcher)
    private val currentPurgeScope = AtomicReference<DiagnosticsPurgeScope?>(null)
    private val liveEvidenceCleanupPending = AtomicBoolean(false)
    private val hostedDeletionDrainRunning = AtomicBoolean(false)
    private val hostedDeletionDrainRequested = AtomicBoolean(false)

    private var currentContext: DiagnosticsCaptureContext? = null
    private var activeCapture: ActiveDiagnosticsCapture? = null

    override val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        capture.closeGate()
        runtimePublisher.closeGate()
        identityTransitions.installGate { transition ->
            // IdentityTransitionBarrier owns the outer lock. Keeping privacy
            // revocation inside it establishes one global order: identity, then
            // diagnostics transport. Uploaders use the same order.
            privacyBarrier.withRevocation {
                val mirroredScope = currentPurgeScope.get()
                val purgeScope = mirroredScope ?: settings.cachedContext()?.toPurgeScope()
                if (transition.affectsCurrentIdentity) {
                    capture.closeGate()
                    runtimePublisher.closeGate()
                    capture.purgeCurrentEvidence()
                    liveEvidenceCleanupPending.set(false)
                }
                when (transition.kind) {
                    IdentityTransitionKind.SIGN_OUT -> {
                        if (transition.purgesPersistentIdentity) {
                            val targetServerId = transition.targetServerId
                            when {
                                targetServerId != null -> settings.purgeLocalServer(
                                    localServerId = targetServerId,
                                    fallbackBinding = purgeScope
                                        ?.takeIf { it.localServerId == targetServerId }
                                        ?.binding,
                                    allowLegacyAllEvidenceFallback = false,
                                )
                                purgeScope != null -> settings.purgeBinding(
                                    binding = purgeScope.binding,
                                    includeLiveCapture = false,
                                )
                                else -> settings.clearCachedContext()
                            }
                        }
                    }
                    IdentityTransitionKind.SERVER_REMOVE -> {
                        val targetServerId = requireNotNull(transition.targetServerId) {
                            "${transition.kind} requires a target server id"
                        }
                        settings.purgeLocalServer(
                            localServerId = targetServerId,
                            fallbackBinding = purgeScope
                                ?.takeIf { it.localServerId == targetServerId }
                                ?.binding,
                        )
                    }
                    IdentityTransitionKind.ACCOUNT_REPLACE -> {
                        val targetServerId = requireNotNull(transition.targetServerId) {
                            "${transition.kind} requires a target server id"
                        }
                        settings.purgeLocalServer(
                            localServerId = targetServerId,
                            fallbackBinding = purgeScope
                                ?.takeIf { it.localServerId == targetServerId }
                                ?.binding,
                            allowLegacyAllEvidenceFallback = false,
                        )
                    }
                    else -> Unit
                }
                if (transition.affectsCurrentIdentity) currentPurgeScope.set(null)
                commands.trySend(
                    Command.IdentityWillChange(
                        kind = transition.kind,
                        previousBinding = purgeScope?.binding,
                        affectsCurrentIdentity = transition.affectsCurrentIdentity,
                        purgesPersistentIdentity = transition.purgesPersistentIdentity,
                    ),
                )
            }
        }
        actorScope.launch {
            for (command in commands) handle(command)
        }
        scope.launch {
            identityTransitions.transitions.collect { transition ->
                if (transition.phase == IdentityTransitionPhase.DID_CHANGE) {
                    commands.send(Command.IdentityDidChange)
                }
            }
        }
        commands.trySend(Command.Refresh())
    }

    override suspend fun refresh() = request { Command.Refresh(it) }

    override suspend fun setConsent(mode: DiagnosticsConsentMode, expectedNoticeVersion: Int?) =
        request { Command.SetConsent(mode, expectedNoticeVersion, it) }

    override suspend fun setDestination(destinationKind: DiagnosticsDestinationKind) =
        request { Command.SetDestination(destinationKind, it) }

    override suspend fun setDebugLogging(enabled: Boolean) =
        request { Command.SetDebugLogging(enabled, it) }

    override suspend fun captureNow(): String? = requestResult { Command.CaptureNow(it) }

    override suspend fun startTimedCapture() = request { Command.StartTimedCapture(it) }

    override suspend fun stopTimedCapture(): String? = requestResult { Command.StopTimedCapture(it) }

    override suspend fun cancelTimedCapture() = request { Command.CancelTimedCapture(it) }

    override suspend fun upload(reportId: String, expectedNoticeVersion: Int?): DiagnosticsUploadDecision =
        requestResult { Command.Upload(reportId, expectedNoticeVersion, it) }

    override suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision =
        requestResult { Command.UploadAutomatically(reportId, it) }

    override suspend fun delete(reportId: String): Boolean = requestResult { Command.Delete(reportId, it) }

    override suspend fun decline(reportId: String) = request { Command.Decline(reportId, it) }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.IdentityWillChange -> identityWillChangeOwned(command)
            Command.IdentityDidChange -> {
                currentContext = null
                refreshOwnedState()
            }
            is Command.Refresh -> completeOptional(command.completion) { refreshOwnedState() }
            is Command.SetConsent -> complete(command.completion) {
                setConsentOwned(command.mode, command.expectedNoticeVersion)
            }
            is Command.SetDestination -> complete(command.completion) { setDestinationOwned(command.destinationKind) }
            is Command.SetDebugLogging -> complete(command.completion) { setDebugLoggingOwned(command.enabled) }
            is Command.CaptureNow -> completeResult(command.completion) { captureNowOwned() }
            is Command.StartTimedCapture -> complete(command.completion) { startTimedCaptureOwned() }
            is Command.StopTimedCapture -> completeResult(command.completion) { stopTimedCaptureOwned() }
            is Command.CancelTimedCapture -> complete(command.completion) { cancelTimedCaptureOwned(false) }
            is Command.Upload -> completeResult(command.completion) {
                uploadOwned(command.reportId, command.expectedNoticeVersion)
            }
            is Command.UploadAutomatically -> completeResult(command.completion) {
                uploadAutomaticallyOwned(command.reportId)
            }
            is Command.Delete -> completeResult(command.completion) { deleteOwned(command.reportId) }
            is Command.Decline -> complete(command.completion) { declineOwned(command.reportId) }
        }
    }

    private suspend fun refreshOwnedState() {
        try {
            storedEvidenceReconciler.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            val selectedDestination = runCatching { settings.destinationKind() }
                .getOrDefault(mutableState.value.destinationKind)
            failClosedUnresolvedRefresh(selectedDestination)
            return
        }
        scheduleHostedDeletionDrain()
        val selectedDestination = runCatching { settings.destinationKind() }
            .getOrDefault(DiagnosticsDestinationKind.HOSTED)
        val resolved = runCatching { identity.resolve(requirePersistentCapture = true) }.getOrNull()
        try {
            capture.reconcileStoredEvidence()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            capture.closeGate()
            runtimePublisher.closeGate()
            currentContext = null
            val active = activeCapture
            activeCapture = null
            runCatching { if (active != null) capture.cancel(active) }
            runCatching { capture.setDebugLogging(null, false) }
            runCatching { capture.setPersistentBreadcrumbs(null, false) }
            val purgeFailure = runCatching { capture.purgeCurrentEvidence() }.exceptionOrNull()
            liveEvidenceCleanupPending.set(purgeFailure != null)
            mutableState.value = mutableState.value.copy(
                availability = DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE,
                profileEligible = resolved?.profileEligible == true,
                consent = DiagnosticsConsentMode.ASK,
                debugLogging = false,
                pending = emptyList(),
                prompt = null,
                timedCapture = TimedCaptureState(),
                sentHistory = emptyList(),
                destinationKind = selectedDestination,
                allowsAutomaticUpload = selectedDestination.allowsAutomaticUpload,
                retentionDays = resolved?.retentionDays ?: mutableState.value.retentionDays,
            )
            return
        }
        val previous = currentContext
        if (
            activeCapture != null &&
            (resolved == null || !resolved.profileEligible || activeCapture?.identityKey != resolved.identityKey)
        ) {
            invalidateActiveCapture()
        }
        if (previous != null && resolved?.identityKey != previous.identityKey) capture.closeGate()

        if (resolved == null) {
            currentContext = null
            runCatching { capture.setDebugLogging(null, false) }
            runCatching { capture.setPersistentBreadcrumbs(null, false) }
            runtimePublisher.closeGate()
            val cached = trustedCachedContext()
            try {
                settings.retryPendingErasures(cached?.binding)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    availability = DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE,
                    profileEligible = false,
                    consent = DiagnosticsConsentMode.NEVER,
                    debugLogging = false,
                    pending = emptyList(),
                    prompt = null,
                    sentHistory = emptyList(),
                )
                return
            }
            if (cached != null) currentPurgeScope.set(cached.toPurgeScope())
            val cachedReports = cached?.let { context ->
                runCatching { reports.list(context.binding) }.getOrDefault(emptyList())
            }.orEmpty()
            val cachedConsent = cached?.let { context ->
                runCatching { settings.consent(context.binding, context.noticeVersion).mode }
                    .getOrDefault(DiagnosticsConsentMode.ASK)
            } ?: DiagnosticsConsentMode.ASK
            mutableState.value = mutableState.value.copy(
                availability = DiagnosticsAvailabilityUi.OFFLINE,
                profileEligible = cached?.profileEligible == true,
                consent = cachedConsent,
                debugLogging = runCatching { settings.debugLogging() }.getOrDefault(false),
                pending = cachedReports.map { report -> report.summary(cached?.retentionDays ?: 7) },
                prompt = null,
                sentHistory = cached?.let { context ->
                    runCatching { settings.sentHistory(context.binding) }.getOrDefault(emptyList())
                }.orEmpty(),
                destinationKind = selectedDestination,
                allowsAutomaticUpload = selectedDestination.allowsAutomaticUpload,
                retentionDays = cached?.retentionDays ?: selectedDestination.defaultRetentionDays,
            )
            return
        }
        try {
            identityTransitions.withCurrentGeneration(resolved.ownershipGeneration) {
                settings.retryPendingErasures(resolved.binding)
                Unit
            } ?: return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failClosedEligibleRefresh(resolved, selectedDestination)
            return
        }
        if (!resolved.profileEligible) {
            currentContext = resolved
            runCatching { capture.setDebugLogging(null, false) }
            runCatching { capture.setPersistentBreadcrumbs(null, false) }
            runtimePublisher.closeGate()
            runCatching { settings.clearCachedContext(resolved.binding) }
            mutableState.value = mutableState.value.copy(
                availability = DiagnosticsAvailabilityUi.INELIGIBLE,
                profileEligible = false,
                consent = DiagnosticsConsentMode.ASK,
                debugLogging = false,
                pending = emptyList(),
                prompt = null,
                sentHistory = emptyList(),
                destinationKind = selectedDestination,
                allowsAutomaticUpload = selectedDestination.allowsAutomaticUpload,
                retentionDays = resolved.retentionDays,
            )
            return
        }

        val liveCaptureContext = if (resolved.destinationKind == DiagnosticsDestinationKind.HOSTED) {
            runCatching { identity.resolveForCapture(requirePersistentCapture = true) }
                .getOrNull()
                ?.takeIf { live ->
                    live.profileEligible &&
                        live.status == DiagnosticsAvailabilityStatus.AVAILABLE &&
                        live.identityKey == resolved.identityKey &&
                        live.destinationKind == resolved.destinationKind &&
                        live.ownershipGeneration == resolved.ownershipGeneration
                }
        } else {
            resolved
        }

        val guardedRefresh = try {
            identityTransitions.withCurrentGeneration(resolved.ownershipGeneration) {
                if (liveEvidenceCleanupPending.get()) {
                    capture.closeGate()
                    runtimePublisher.closeGate()
                    capture.purgeCurrentEvidence()
                    liveEvidenceCleanupPending.set(false)
                }
                // The local-server -> binding index is the durable erasure authority for an
                // inactive server. Do not create or re-enable any identity-scoped evidence
                // until that index and the matching cached context are committed atomically.
                settings.cacheContext(resolved)
                currentPurgeScope.set(resolved.toPurgeScope())

                val consent = settings.consent(resolved.binding, resolved.noticeVersion)
                val debugLogging = runCatching { settings.debugLogging() }.getOrDefault(false)
                if (consent.mode == DiagnosticsConsentMode.NEVER) {
                    runtimePublisher.closeGate()
                    capture.setDebugLogging(null, false)
                    capture.setPersistentBreadcrumbs(null, false)
                } else if (liveCaptureContext != null) {
                    // The generation mutex covers every commit that can publish a crash
                    // snapshot or persist identity-owned incident/capture evidence. An
                    // identity mutation either waits and purges this work, or wins first and
                    // prevents this block from running.
                    runtimePublisher.publish(liveCaptureContext)
                    incidentCollector.collect(liveCaptureContext, consent.mode)
                    capture.setDebugLogging(
                        liveCaptureContext,
                        debugLogging && activeCapture == null,
                    )
                    capture.setPersistentBreadcrumbs(liveCaptureContext, true)
                } else {
                    runtimePublisher.closeGate()
                    capture.setDebugLogging(null, false)
                    capture.setPersistentBreadcrumbs(null, false)
                }

                // A store cleanup/enumeration failure is a privacy boundary,
                // not an empty report list. Let the outer fail-closed path keep
                // every evidence gate shut until strict cleanup can succeed.
                val pendingReports = reports.list(resolved.binding)
                reports.hostedReadyReports()
                    .filter { receipt -> receipt.binding == resolved.binding }
                    .forEach { receipt ->
                        settings.recordSent(
                            binding = receipt.binding,
                            shortId = receipt.shortId,
                            sentAtEpochMs = receipt.readyAtEpochMs,
                            state = HostedDiagnosticsReportState.READY.wireValue,
                        )
                    }
                val history = runCatching { settings.sentHistory(resolved.binding) }.getOrDefault(emptyList())
                currentContext = resolved
                EligibleRefresh(
                    consent = consent,
                    debugLogging = debugLogging,
                    pendingReports = pendingReports,
                    history = history,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failClosedEligibleRefresh(resolved, selectedDestination)
            return
        } ?: return

        val consent = guardedRefresh.consent
        val debugLogging = guardedRefresh.debugLogging
        val pendingReports = guardedRefresh.pendingReports
        val summaries = pendingReports.map { report -> report.summary(resolved.retentionDays) }
        val promptReports = pendingReports.filter { report ->
            consent.mode == DiagnosticsConsentMode.ASK &&
                report.state.status != PendingReportStatus.PROCESSING &&
                !runCatching {
                    reports.isThrottled(promptThrottleKey(report), PROMPT_THROTTLE_MS)
                }.getOrDefault(true)
        }
        mutableState.value = mutableState.value.copy(
            availability = resolved.status.toUiAvailability(),
            profileEligible = resolved.profileEligible,
            consent = consent.mode,
            debugLogging = debugLogging,
            pending = summaries,
            prompt = promptReports.firstOrNull()?.let { primary ->
                DiagnosticsPrompt(
                    reportId = primary.id,
                    reportType = primary.manifest.report.type,
                    capturedAt = primary.manifest.report.capturedAt,
                    reportIds = promptReports.map(PendingReport::id),
                    noticeVersion = resolved.noticeVersion,
                )
            },
            sentHistory = guardedRefresh.history,
            destinationKind = resolved.destinationKind,
            allowsAutomaticUpload = resolved.destinationKind.allowsAutomaticUpload,
            retentionDays = resolved.retentionDays,
        )
        if (
            resolved.destinationKind.allowsAutomaticUpload &&
            consent.mode == DiagnosticsConsentMode.ALWAYS &&
            resolved.status == DiagnosticsAvailabilityStatus.AVAILABLE
        ) {
            pendingReports.forEach { report -> uploadScheduler.enqueue(report.id) }
        }
    }

    private suspend fun failClosedEligibleRefresh(
        resolved: DiagnosticsCaptureContext,
        selectedDestination: DiagnosticsDestinationKind,
    ) {
        capture.closeGate()
        runtimePublisher.closeGate()
        currentContext = null
        val active = activeCapture
        activeCapture = null
        runCatching { if (active != null) capture.cancel(active) }
        runCatching { capture.setDebugLogging(null, false) }
        runCatching { capture.setPersistentBreadcrumbs(null, false) }
        val purgeFailure = runCatching { capture.purgeCurrentEvidence() }.exceptionOrNull()
        liveEvidenceCleanupPending.set(purgeFailure != null)
        mutableState.value = mutableState.value.copy(
            availability = DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE,
            profileEligible = true,
            consent = DiagnosticsConsentMode.ASK,
            debugLogging = false,
            pending = emptyList(),
            prompt = null,
            timedCapture = TimedCaptureState(),
            sentHistory = emptyList(),
            destinationKind = selectedDestination,
            allowsAutomaticUpload = selectedDestination.allowsAutomaticUpload,
            retentionDays = resolved.retentionDays,
        )
    }

    private suspend fun failClosedUnresolvedRefresh(selectedDestination: DiagnosticsDestinationKind) {
        capture.closeGate()
        runtimePublisher.closeGate()
        currentContext = null
        val active = activeCapture
        activeCapture = null
        runCatching { if (active != null) capture.cancel(active) }
        runCatching { capture.setDebugLogging(null, false) }
        runCatching { capture.setPersistentBreadcrumbs(null, false) }
        val purgeFailure = runCatching { capture.purgeCurrentEvidence() }.exceptionOrNull()
        liveEvidenceCleanupPending.set(purgeFailure != null)
        mutableState.value = mutableState.value.copy(
            availability = DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE,
            profileEligible = false,
            consent = DiagnosticsConsentMode.ASK,
            debugLogging = false,
            pending = emptyList(),
            prompt = null,
            timedCapture = TimedCaptureState(),
            sentHistory = emptyList(),
            destinationKind = selectedDestination,
            allowsAutomaticUpload = selectedDestination.allowsAutomaticUpload,
            retentionDays = selectedDestination.defaultRetentionDays,
        )
    }

    private suspend fun setConsentOwned(
        mode: DiagnosticsConsentMode,
        expectedNoticeVersion: Int?,
    ) {
        if (expectedNoticeVersion != null) refreshOwnedState()
        val context = currentEligibleContext() ?: return
        if (mode == DiagnosticsConsentMode.ALWAYS && !context.destinationKind.allowsAutomaticUpload) return
        if (expectedNoticeVersion != null && context.noticeVersion != expectedNoticeVersion) return
        val committed = identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
            privacyBarrier.withRevocation {
                if (mode == DiagnosticsConsentMode.NEVER) {
                    capture.closeGate()
                    runtimePublisher.closeGate()
                    val active = activeCapture
                    activeCapture = null
                    if (active != null) runCatching { capture.cancel(active) }
                    mutableState.value = mutableState.value.copy(timedCapture = TimedCaptureState())
                }
                settings.setConsent(context.binding, mode, context.noticeVersion)
            }
        }
        if (committed == null) return
        refreshOwnedState()
    }

    private suspend fun setDestinationOwned(destinationKind: DiagnosticsDestinationKind) {
        if (settings.destinationKind() == destinationKind) return
        privacyBarrier.withRevocation {
            capture.closeGate()
            runtimePublisher.closeGate()
            val active = activeCapture
            activeCapture = null
            if (active != null) runCatching { capture.cancel(active) }
            runCatching { settings.clearCachedContext() }
            settings.setDestinationKind(destinationKind)
            currentContext = null
            mutableState.value = mutableState.value.copy(timedCapture = TimedCaptureState())
        }
        refreshOwnedState()
    }

    private suspend fun setDebugLoggingOwned(enabled: Boolean) {
        val context = currentEligibleContext() ?: return
        val allowed = enabled && mutableState.value.consent != DiagnosticsConsentMode.NEVER
        identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
            settings.setDebugLogging(allowed)
            if (activeCapture == null) capture.setDebugLogging(context, allowed)
            mutableState.value = mutableState.value.copy(debugLogging = allowed)
            Unit
        }
    }

    private suspend fun captureNowOwned(): String? {
        val context = liveCaptureContext() ?: return null
        val captured = identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
            GuardedValue(runCatching { capture.captureNow(context) }.getOrNull())
        } ?: return null
        val report = captured.value ?: return null
        refreshOwnedState()
        return report.id
    }

    private suspend fun startTimedCaptureOwned() {
        val context = liveCaptureContext() ?: return
        identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
            activeCapture?.let { previous -> runCatching { capture.cancel(previous) } }
            val active = runCatching { capture.start(context) }.getOrNull() ?: return@withCurrentGeneration Unit
            activeCapture = active
            mutableState.value = mutableState.value.copy(
                timedCapture = TimedCaptureState(
                    status = TimedCaptureStatus.ACTIVE,
                    generation = active.generation,
                    startedAtEpochMs = active.startedAtEpochMs,
                ),
            )
            Unit
        }
    }

    private suspend fun stopTimedCaptureOwned(): String? {
        val active = activeCapture ?: return null
        val context = currentEligibleContext()
        if (context == null || context.identityKey != active.identityKey) {
            invalidateActiveCapture()
            return null
        }
        val stopped = identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
            activeCapture = null
            val report = runCatching { capture.stop(active, context) }.getOrNull()
            mutableState.value = mutableState.value.copy(timedCapture = TimedCaptureState())
            GuardedValue(report)
        } ?: return null
        refreshOwnedState()
        return stopped.value?.id
    }

    private suspend fun cancelTimedCaptureOwned(invalidated: Boolean) {
        val active = activeCapture
        activeCapture = null
        if (active != null) runCatching { capture.cancel(active) }
        if (!invalidated) {
            val context = currentEligibleContext()
            if (context != null && mutableState.value.debugLogging) {
                identityTransitions.withCurrentGeneration(context.ownershipGeneration) {
                    runCatching { capture.setDebugLogging(context, true) }
                    Unit
                }
            }
        }
        mutableState.value = mutableState.value.copy(
            timedCapture = TimedCaptureState(
                status = if (invalidated && active != null) TimedCaptureStatus.INVALIDATED else TimedCaptureStatus.IDLE,
            ),
        )
    }

    private suspend fun invalidateActiveCapture() {
        currentContext = null
        cancelTimedCaptureOwned(invalidated = true)
    }

    private suspend fun identityWillChangeOwned(command: Command.IdentityWillChange) {
        if (!command.affectsCurrentIdentity) return
        capture.closeGate()
        runtimePublisher.closeGate()
        invalidateActiveCapture()
        if (
            command.kind in DESTRUCTIVE_IDENTITY_TRANSITIONS &&
            command.purgesPersistentIdentity &&
            command.previousBinding != null
        ) {
            // The synchronous transition gate already removed evidence and
            // settings before identity mutation. Repeat the metadata half now
            // that any actor-owned upload has settled, closing the narrow
            // response-after-purge window without re-running live capture
            // deletion or risking a gate/actor lock inversion.
            runCatching { settings.scrubBindingMetadata(command.previousBinding) }
        }
        // The inline gate is authoritative. Repeat the live-evidence purge after
        // actor convergence so a stale queued command can never reopen a capture.
        liveEvidenceCleanupPending.set(
            runCatching { capture.purgeCurrentEvidence() }.isFailure,
        )
        if (command.kind !in DESTRUCTIVE_IDENTITY_TRANSITIONS) {
            runCatching { settings.clearCachedContext(command.previousBinding) }
        }
    }

    private suspend fun uploadOwned(
        reportId: String,
        expectedNoticeVersion: Int?,
    ): DiagnosticsUploadDecision {
        val report = reports.load(reportId) ?: return DiagnosticsUploadDecision.KeptInvalid
        val context = currentEligibleContext() ?: return DiagnosticsUploadDecision.KeptUnavailable
        if (expectedNoticeVersion != null && context.noticeVersion != expectedNoticeVersion) {
            return DiagnosticsUploadDecision.KeptConsentReviewRequired
        }
        if (
            mutableState.value.consent == DiagnosticsConsentMode.NEVER &&
            report.manifest.report.type != org.siloserver.silo.model.diagnostics.DiagnosticsReportType.MANUAL
        ) {
            return DiagnosticsUploadDecision.KeptUnavailable
        }
        if (!report.binding.matches(context)) return DiagnosticsUploadDecision.KeptIdentityChanged
        val decision = if (expectedNoticeVersion == null) {
            uploader.upload(reportId)
        } else {
            uploader.upload(reportId, expectedNoticeVersion)
        }
        if (decision is DiagnosticsUploadDecision.HostedProcessing) {
            uploadScheduler.enqueue(reportId)
        }
        refreshOwnedState()
        return decision
    }

    private suspend fun uploadAutomaticallyOwned(reportId: String): DiagnosticsUploadDecision {
        val report = reports.load(reportId) ?: return DiagnosticsUploadDecision.KeptInvalid
        val context = currentEligibleContext() ?: return DiagnosticsUploadDecision.KeptUnavailable
        val selectedDestination = settings.destinationKind()
        if (
            selectedDestination != report.binding.destinationKind ||
            !report.binding.matches(context) ||
            report.binding.destinationKind != context.destinationKind
        ) {
            return DiagnosticsUploadDecision.KeptIdentityChanged
        }
        val hostedStatusPoll = report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED &&
            report.state.hostedRemoteShortId != null
        val consent = settings.consent(context.binding, context.noticeVersion).mode
        if (!hostedStatusPoll && consent != DiagnosticsConsentMode.ALWAYS) {
            return DiagnosticsUploadDecision.KeptConsentReviewRequired
        }
        val decision = if (hostedStatusPoll) {
            uploader.upload(reportId)
        } else {
            uploader.uploadAutomatically(reportId)
        }
        refreshOwnedState()
        return decision
    }

    private suspend fun deleteOwned(reportId: String): Boolean {
        val deleted = privacyBarrier.withRevocation {
            val report = reports.load(reportId)
            val deletionBinding = report?.binding?.binding ?: reports.hostedReadyBinding(reportId) ?: return@withRevocation true
            val liveBinding = currentEligibleContext()?.binding
            val cachedBinding = if (liveBinding == null) {
                trustedCachedContext()?.binding
            } else {
                null
            }
            if (deletionBinding != (liveBinding ?: cachedBinding)) return@withRevocation false
            try {
                reports.stageHostedDeletionAndDelete(reportId)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Intent-covered evidence is deliberately hidden from load(), even
                // when physical cleanup failed. Never report UI deletion success
                // from that absence; the durable intent remains retryable.
                false
            }
        }
        if (!deleted) return false
        hostedDeletionScheduler.enqueue()
        refreshOwnedState()
        return true
    }

    private suspend fun drainHostedDeletionIntents(): Boolean {
        var completedAll = true
        reports.hostedDeletionIntents().forEach { reportId ->
            val completed = try {
                hostedReportDeleter.delete(reportId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (completed) {
                try {
                    reports.completeHostedDeletion(reportId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    completedAll = false
                }
            } else {
                completedAll = false
            }
        }
        return completedAll
    }

    private fun scheduleHostedDeletionDrain() {
        hostedDeletionDrainRequested.set(true)
        if (!hostedDeletionDrainRunning.compareAndSet(false, true)) return
        actorScope.launch {
            try {
                while (hostedDeletionDrainRequested.getAndSet(false)) {
                    if (!drainHostedDeletionIntents()) hostedDeletionScheduler.enqueue()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The durable intent remains available for the next refresh.
            } finally {
                hostedDeletionDrainRunning.set(false)
                if (hostedDeletionDrainRequested.get()) scheduleHostedDeletionDrain()
            }
        }
    }

    private suspend fun declineOwned(reportId: String) {
        val report = reports.load(reportId) ?: return
        val liveBinding = currentEligibleContext()?.binding
        val cachedBinding = if (liveBinding == null) {
            trustedCachedContext()?.binding
        } else {
            null
        }
        if (report.binding.binding != (liveBinding ?: cachedBinding)) return
        reports.markThrottled(promptThrottleKey(report), nowMs())
        mutableState.value = mutableState.value.copy(prompt = null)
    }

    private fun currentEligibleContext(): DiagnosticsCaptureContext? =
        currentContext?.takeIf(DiagnosticsCaptureContext::profileEligible)

    private suspend fun liveCaptureContext(): DiagnosticsCaptureContext? {
        val current = currentEligibleContext() ?: return null
        val live = runCatching { identity.resolveForCapture(requirePersistentCapture = true) }.getOrNull()
            ?.takeIf { it.profileEligible && it.status == DiagnosticsAvailabilityStatus.AVAILABLE }
            ?: return null
        if (live.identityKey != current.identityKey || live.destinationKind != current.destinationKind) {
            refreshOwnedState()
            return null
        }
        return live
    }

    private suspend fun trustedCachedContext(): CachedDiagnosticsContext? =
        runCatching { settings.cachedContext() }.getOrNull()?.takeIf { cached ->
            runCatching { identity.matchesCachedIdentity(cached) }.getOrDefault(false)
        }

    private suspend fun request(command: (CompletableDeferred<Unit>) -> Command) {
        requestResult(command)
    }

    private suspend fun <T> requestResult(command: (CompletableDeferred<T>) -> Command): T {
        check(started.get()) { "DiagnosticsCoordinator.start() must be called first" }
        val completion = CompletableDeferred<T>()
        commands.send(command(completion))
        return completion.await()
    }

    private suspend fun complete(completion: CompletableDeferred<Unit>, block: suspend () -> Unit) {
        runCatching { block() }.fold(completion::complete, completion::completeExceptionally)
    }

    private suspend fun <T> completeResult(completion: CompletableDeferred<T>, block: suspend () -> T) {
        runCatching { block() }.fold(completion::complete, completion::completeExceptionally)
    }

    private sealed interface Command {
        data class IdentityWillChange(
            val kind: IdentityTransitionKind,
            val previousBinding: DiagnosticsBinding?,
            val affectsCurrentIdentity: Boolean,
            val purgesPersistentIdentity: Boolean,
        ) : Command
        data object IdentityDidChange : Command
        data class Refresh(val completion: CompletableDeferred<Unit>? = null) : Command
        data class SetConsent(
            val mode: DiagnosticsConsentMode,
            val expectedNoticeVersion: Int?,
            val completion: CompletableDeferred<Unit>,
        ) : Command
        data class SetDestination(
            val destinationKind: DiagnosticsDestinationKind,
            val completion: CompletableDeferred<Unit>,
        ) : Command
        data class SetDebugLogging(val enabled: Boolean, val completion: CompletableDeferred<Unit>) : Command
        data class CaptureNow(val completion: CompletableDeferred<String?>) : Command
        data class StartTimedCapture(val completion: CompletableDeferred<Unit>) : Command
        data class StopTimedCapture(val completion: CompletableDeferred<String?>) : Command
        data class CancelTimedCapture(val completion: CompletableDeferred<Unit>) : Command
        data class Upload(
            val reportId: String,
            val expectedNoticeVersion: Int?,
            val completion: CompletableDeferred<DiagnosticsUploadDecision>,
        ) : Command
        data class UploadAutomatically(
            val reportId: String,
            val completion: CompletableDeferred<DiagnosticsUploadDecision>,
        ) : Command
        data class Delete(val reportId: String, val completion: CompletableDeferred<Boolean>) : Command
        data class Decline(val reportId: String, val completion: CompletableDeferred<Unit>) : Command
    }

    private suspend fun completeOptional(completion: CompletableDeferred<Unit>?, block: suspend () -> Unit) {
        if (completion == null) {
            runCatching { block() }
        } else {
            complete(completion, block)
        }
    }

    private companion object {
        const val PROMPT_THROTTLE_MS = 24 * 60 * 60 * 1_000L
        val DESTRUCTIVE_IDENTITY_TRANSITIONS = setOf(
            IdentityTransitionKind.ACCOUNT_REPLACE,
            IdentityTransitionKind.SIGN_OUT,
            IdentityTransitionKind.SERVER_REMOVE,
        )
    }
}

private data class DiagnosticsPurgeScope(
    val localServerId: String?,
    val binding: DiagnosticsBinding,
)

private data class EligibleRefresh(
    val consent: DiagnosticsConsentRecord,
    val debugLogging: Boolean,
    val pendingReports: List<PendingReport>,
    val history: List<SentDiagnosticsReport>,
)

private data class GuardedValue<T>(val value: T)

private fun DiagnosticsCaptureContext.toPurgeScope() = DiagnosticsPurgeScope(localServerId, binding)

private fun CachedDiagnosticsContext.toPurgeScope() = DiagnosticsPurgeScope(localServerId, binding)

private fun DiagnosticsAvailabilityStatus.toUiAvailability(): DiagnosticsAvailabilityUi = when (this) {
    DiagnosticsAvailabilityStatus.AVAILABLE -> DiagnosticsAvailabilityUi.AVAILABLE
    DiagnosticsAvailabilityStatus.DISABLED -> DiagnosticsAvailabilityUi.DISABLED
    DiagnosticsAvailabilityStatus.STORAGE_UNAVAILABLE -> DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE
}

private fun PendingReport.summary(@Suppress("UNUSED_PARAMETER") retentionDays: Int): DiagnosticsReportSummary = DiagnosticsReportSummary(
    id = id,
    type = manifest.report.type,
    capturedAt = manifest.report.capturedAt,
    capturedAtEpochMs = state.capturedAtEpochMs,
    // This is local pending-evidence expiry, not the collector's post-upload
    // retention policy shown in settings.
    expiresAtEpochMs = state.capturedAtEpochMs + PENDING_DIAGNOSTICS_RETENTION_DAYS * MILLIS_PER_DAY,
    evidenceBytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length),
    destinationServerInstanceId = manifest.destination.serverInstanceId,
    capturedProfileId = binding.profileId,
    archiveEntries = manifest.archive.entries,
    uploadStatus = state.status,
    uploadErrorCode = state.errorCode,
    destinationKind = binding.destinationKind,
)

private fun promptThrottleKey(report: PendingReport): String = "prompt:${report.state.fingerprint}"

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
