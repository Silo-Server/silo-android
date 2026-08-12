package org.siloserver.silo.common.diagnostics

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class DefaultDiagnosticsRuntimePublisher(
    private val ledger: DiagnosticsRunLedger,
    private val logBuffer: DiagnosticsLogBuffer,
    private val deviceSnapshots: DeviceSnapshotCollector,
    private val deviceSnapshotCache: DeviceSnapshotCache,
    private val redactionTokens: DiagnosticsRedactionTokenProvider,
    private val playbackSessions: DiagnosticsPlaybackSessionTracker = DiagnosticsPlaybackSessionTracker(),
    private val processStartedAtEpochMs: Long = System.currentTimeMillis(),
    private val captureSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : DiagnosticsRuntimePublisher {
    private val active = AtomicReference<PublishedRuntime?>(null)

    override fun closeGate() {
        playbackSessions.close()
        active.set(null)
        CrashCapture.closeGate()
    }

    override suspend fun publish(context: DiagnosticsCaptureContext) {
        require(context.profileEligible)
        CrashCapture.installLogBuffer(logBuffer)
        val playbackScope = playbackSessions.open(context.identityKey)
        val existing = active.get()?.takeIf { it.identityKey == context.identityKey }
        val published = existing ?: run {
            val captureSessionId = captureSessionIdFactory().take(128)
            val runToken = ledger.beginRun(context, processStartedAtEpochMs, captureSessionId)
            PublishedRuntime(context.identityKey, captureSessionId, runToken).also(active::set)
        }
        val snapshot = runCatching {
            deviceSnapshots.capture(org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance.PRE_FAILURE)
        }.getOrNull()
        if (snapshot != null) deviceSnapshotCache.update(snapshot)
        val logs = logBuffer.snapshot()
        val tokens = runCatching { redactionTokens.tokens(context.destinationKind) }.getOrDefault(emptyList())
        playbackSessions.commitIfCurrent(playbackScope) { playbackSessionIds ->
            CrashCapture.updateSnapshot(CrashRuntimeSnapshot(
                identityKey = context.identityKey,
                binding = PendingReportBinding(
                    serverInstanceId = context.binding.serverInstanceId,
                    accountUserId = context.binding.accountUserId,
                    profileId = context.profileId,
                    ownershipGeneration = context.ownershipGeneration,
                    destinationKind = context.destinationKind,
                ),
                captureSessionId = published.captureSessionId,
                runToken = published.runToken,
                playbackSessionIds = if (context.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                    emptyList()
                } else {
                    playbackSessionIds
                },
                deviceSnapshotJson = deviceSnapshotCache.currentBytes()?.decodeToString(),
                logLines = logs.lines,
                logDroppedCount = logs.droppedCount,
                logTornCount = logs.tornCount,
                logGeneration = logs.generation,
                redactionTokens = tokens,
            ))
        }
    }

    private data class PublishedRuntime(
        val identityKey: DiagnosticsIdentityKey,
        val captureSessionId: String,
        val runToken: String,
    )
}
