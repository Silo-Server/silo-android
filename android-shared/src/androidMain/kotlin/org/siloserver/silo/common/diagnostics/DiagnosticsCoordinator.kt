package org.siloserver.silo.common.diagnostics

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
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
    suspend fun purge(binding: DiagnosticsBinding)
}

fun interface DiagnosticsUploadScheduler {
    fun enqueue(reportId: String)
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

interface DiagnosticsCoordinator {
    val state: StateFlow<DiagnosticsUiState>

    fun start()
    suspend fun refresh()
    suspend fun setConsent(mode: DiagnosticsConsentMode, expectedNoticeVersion: Int? = null)
    suspend fun setDebugLogging(enabled: Boolean)
    suspend fun captureNow(): String?
    suspend fun startTimedCapture()
    suspend fun stopTimedCapture(): String?
    suspend fun cancelTimedCapture()
    suspend fun upload(reportId: String, expectedNoticeVersion: Int? = null): DiagnosticsUploadDecision
    suspend fun delete(reportId: String)
    suspend fun decline(reportId: String)
}

class DefaultDiagnosticsCoordinator(
    private val scope: CoroutineScope,
    private val identity: DiagnosticsIdentityResolver,
    private val identityTransitions: IdentityTransitionBarrier,
    private val settings: DiagnosticsSettingsStore,
    private val reports: PendingReportStore,
    private val capture: DiagnosticsCaptureController,
    private val uploader: DiagnosticsUploader,
    private val uploadScheduler: DiagnosticsUploadScheduler,
    private val runtimePublisher: DiagnosticsRuntimePublisher = DiagnosticsRuntimePublisher.None,
    private val incidentCollector: DiagnosticsIncidentCollector = DiagnosticsIncidentCollector { _, _ -> emptyList() },
    actorDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DiagnosticsCoordinator {
    private val started = AtomicBoolean(false)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    private val actorScope = CoroutineScope(scope.coroutineContext + actorDispatcher)

    private var currentContext: DiagnosticsCaptureContext? = null
    private var activeCapture: ActiveDiagnosticsCapture? = null

    override val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        capture.closeGate()
        runtimePublisher.closeGate()
        identityTransitions.installGate {
            capture.closeGate()
            runtimePublisher.closeGate()
            commands.trySend(Command.IdentityWillChange(it.kind))
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

    override suspend fun setDebugLogging(enabled: Boolean) =
        request { Command.SetDebugLogging(enabled, it) }

    override suspend fun captureNow(): String? = requestResult { Command.CaptureNow(it) }

    override suspend fun startTimedCapture() = request { Command.StartTimedCapture(it) }

    override suspend fun stopTimedCapture(): String? = requestResult { Command.StopTimedCapture(it) }

    override suspend fun cancelTimedCapture() = request { Command.CancelTimedCapture(it) }

    override suspend fun upload(reportId: String, expectedNoticeVersion: Int?): DiagnosticsUploadDecision =
        requestResult { Command.Upload(reportId, expectedNoticeVersion, it) }

    override suspend fun delete(reportId: String) = request { Command.Delete(reportId, it) }

    override suspend fun decline(reportId: String) = request { Command.Decline(reportId, it) }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.IdentityWillChange -> identityWillChangeOwned(command.kind)
            Command.IdentityDidChange -> {
                currentContext = null
                refreshOwnedState()
            }
            is Command.Refresh -> completeOptional(command.completion) { refreshOwnedState() }
            is Command.SetConsent -> complete(command.completion) {
                setConsentOwned(command.mode, command.expectedNoticeVersion)
            }
            is Command.SetDebugLogging -> complete(command.completion) { setDebugLoggingOwned(command.enabled) }
            is Command.CaptureNow -> completeResult(command.completion) { captureNowOwned() }
            is Command.StartTimedCapture -> complete(command.completion) { startTimedCaptureOwned() }
            is Command.StopTimedCapture -> completeResult(command.completion) { stopTimedCaptureOwned() }
            is Command.CancelTimedCapture -> complete(command.completion) { cancelTimedCaptureOwned(false) }
            is Command.Upload -> completeResult(command.completion) {
                uploadOwned(command.reportId, command.expectedNoticeVersion)
            }
            is Command.Delete -> complete(command.completion) { deleteOwned(command.reportId) }
            is Command.Decline -> complete(command.completion) { declineOwned(command.reportId) }
        }
    }

    private suspend fun refreshOwnedState() {
        val resolved = runCatching { identity.resolve(requirePersistentCapture = true) }.getOrNull()
        val previous = currentContext
        if (
            activeCapture != null &&
            (resolved == null || !resolved.profileEligible || activeCapture?.identityKey != resolved.identityKey)
        ) {
            invalidateActiveCapture()
        }
        if (previous != null && resolved?.identityKey != previous.identityKey) capture.closeGate()
        currentContext = resolved

        if (resolved == null) {
            runCatching { capture.setDebugLogging(null, false) }
            runCatching { capture.setPersistentBreadcrumbs(null, false) }
            runtimePublisher.closeGate()
            val cached = trustedCachedContext()
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
            )
            return
        }
        if (!resolved.profileEligible) {
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
            )
            return
        }

        runCatching { settings.cacheContext(resolved) }
        val consent = runCatching { settings.consent(resolved.binding, resolved.noticeVersion) }
            .getOrElse { DiagnosticsConsentRecord(DiagnosticsConsentMode.ASK, resolved.noticeVersion) }
        if (consent.mode == DiagnosticsConsentMode.NEVER) {
            runtimePublisher.closeGate()
        } else {
            runCatching { runtimePublisher.publish(resolved) }
            runCatching { incidentCollector.collect(resolved, consent.mode) }
        }
        val debugLogging = runCatching { settings.debugLogging() }.getOrDefault(false)
        val pendingReports = runCatching { reports.list(resolved.binding) }.getOrDefault(emptyList())
        val summaries = pendingReports.map { report -> report.summary(resolved.retentionDays) }
        val promptReports = pendingReports.filter { report ->
            consent.mode == DiagnosticsConsentMode.ASK &&
                !runCatching {
                    reports.isThrottled(promptThrottleKey(report), PROMPT_THROTTLE_MS)
                }.getOrDefault(true)
        }
        val history = runCatching { settings.sentHistory(resolved.binding) }.getOrDefault(emptyList())
        runCatching {
            capture.setDebugLogging(
                resolved,
                debugLogging && consent.mode != DiagnosticsConsentMode.NEVER && activeCapture == null,
            )
        }
        runCatching {
            capture.setPersistentBreadcrumbs(resolved, consent.mode != DiagnosticsConsentMode.NEVER)
        }
        mutableState.value = mutableState.value.copy(
            availability = resolved.status.toUiAvailability(),
            profileEligible = true,
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
            sentHistory = history,
        )
        if (consent.mode == DiagnosticsConsentMode.ALWAYS && resolved.status == DiagnosticsAvailabilityStatus.AVAILABLE) {
            pendingReports.forEach { report -> uploadScheduler.enqueue(report.id) }
        }
    }

    private suspend fun setConsentOwned(
        mode: DiagnosticsConsentMode,
        expectedNoticeVersion: Int?,
    ) {
        if (expectedNoticeVersion != null) refreshOwnedState()
        val context = currentEligibleContext() ?: return
        if (expectedNoticeVersion != null && context.noticeVersion != expectedNoticeVersion) return
        if (mode == DiagnosticsConsentMode.NEVER) {
            capture.closeGate()
            val active = activeCapture
            activeCapture = null
            if (active != null) runCatching { capture.cancel(active) }
            mutableState.value = mutableState.value.copy(timedCapture = TimedCaptureState())
        }
        settings.setConsent(context.binding, mode, context.noticeVersion)
        refreshOwnedState()
    }

    private suspend fun setDebugLoggingOwned(enabled: Boolean) {
        val context = currentEligibleContext() ?: return
        val allowed = enabled && mutableState.value.consent != DiagnosticsConsentMode.NEVER
        settings.setDebugLogging(allowed)
        if (activeCapture == null) capture.setDebugLogging(context, allowed)
        mutableState.value = mutableState.value.copy(debugLogging = allowed)
    }

    private suspend fun captureNowOwned(): String? {
        val context = currentEligibleContext() ?: return null
        val report = runCatching { capture.captureNow(context) }.getOrNull() ?: return null
        refreshOwnedState()
        return report.id
    }

    private suspend fun startTimedCaptureOwned() {
        val context = currentEligibleContext() ?: return
        activeCapture?.let { previous -> runCatching { capture.cancel(previous) } }
        val active = runCatching { capture.start(context) }.getOrNull() ?: return
        activeCapture = active
        mutableState.value = mutableState.value.copy(
            timedCapture = TimedCaptureState(
                status = TimedCaptureStatus.ACTIVE,
                generation = active.generation,
                startedAtEpochMs = active.startedAtEpochMs,
            ),
        )
    }

    private suspend fun stopTimedCaptureOwned(): String? {
        val active = activeCapture ?: return null
        val context = currentEligibleContext()
        if (context == null || context.identityKey != active.identityKey) {
            invalidateActiveCapture()
            return null
        }
        activeCapture = null
        val report = runCatching { capture.stop(active, context) }.getOrNull()
        mutableState.value = mutableState.value.copy(timedCapture = TimedCaptureState())
        refreshOwnedState()
        return report?.id
    }

    private suspend fun cancelTimedCaptureOwned(invalidated: Boolean) {
        val active = activeCapture
        activeCapture = null
        if (active != null) runCatching { capture.cancel(active) }
        if (!invalidated) {
            val context = currentEligibleContext()
            if (context != null && mutableState.value.debugLogging) {
                runCatching { capture.setDebugLogging(context, true) }
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

    private suspend fun identityWillChangeOwned(kind: IdentityTransitionKind) {
        val previousBinding = currentContext?.binding
            ?: runCatching { settings.cachedContext()?.binding }.getOrNull()
        invalidateActiveCapture()
        runCatching { settings.clearCachedContext(previousBinding) }
        if (
            previousBinding != null &&
            kind in setOf(IdentityTransitionKind.SIGN_OUT, IdentityTransitionKind.SERVER_REMOVE)
        ) {
            runCatching { settings.purgeBinding(previousBinding) }
        } else if (kind in setOf(IdentityTransitionKind.SIGN_OUT, IdentityTransitionKind.SERVER_REMOVE)) {
            runCatching { settings.clearCachedContext() }
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
        refreshOwnedState()
        return decision
    }

    private suspend fun deleteOwned(reportId: String) {
        val report = reports.load(reportId) ?: return
        val liveBinding = currentEligibleContext()?.binding
        val cachedBinding = if (liveBinding == null) {
            trustedCachedContext()?.binding
        } else {
            null
        }
        if (report.binding.binding == (liveBinding ?: cachedBinding)) reports.delete(reportId)
        refreshOwnedState()
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
        data class IdentityWillChange(val kind: IdentityTransitionKind) : Command
        data object IdentityDidChange : Command
        data class Refresh(val completion: CompletableDeferred<Unit>? = null) : Command
        data class SetConsent(
            val mode: DiagnosticsConsentMode,
            val expectedNoticeVersion: Int?,
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
        data class Delete(val reportId: String, val completion: CompletableDeferred<Unit>) : Command
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
    }
}

private fun DiagnosticsAvailabilityStatus.toUiAvailability(): DiagnosticsAvailabilityUi = when (this) {
    DiagnosticsAvailabilityStatus.AVAILABLE -> DiagnosticsAvailabilityUi.AVAILABLE
    DiagnosticsAvailabilityStatus.DISABLED -> DiagnosticsAvailabilityUi.DISABLED
    DiagnosticsAvailabilityStatus.STORAGE_UNAVAILABLE -> DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE
}

private fun PendingReport.summary(retentionDays: Int): DiagnosticsReportSummary = DiagnosticsReportSummary(
    id = id,
    type = manifest.report.type,
    capturedAt = manifest.report.capturedAt,
    capturedAtEpochMs = state.capturedAtEpochMs,
    expiresAtEpochMs = state.capturedAtEpochMs + retentionDays.coerceAtLeast(1) * MILLIS_PER_DAY,
    evidenceBytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length),
    destinationServerInstanceId = manifest.destination.serverInstanceId,
    capturedProfileId = binding.profileId,
    archiveEntries = manifest.archive.entries,
    uploadStatus = state.status,
    uploadErrorCode = state.errorCode,
)

private fun promptThrottleKey(report: PendingReport): String = "prompt:${report.state.fingerprint}"

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
