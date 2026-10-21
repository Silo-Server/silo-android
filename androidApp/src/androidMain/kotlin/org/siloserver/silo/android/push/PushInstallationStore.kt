package org.siloserver.silo.android.push

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.notifications.PushDeviceRegisterRequest
import org.siloserver.silo.model.notifications.PushDeviceRegisterResponse
import org.siloserver.silo.network.SiloJson

@Serializable
internal data class PushInstallation(
    val serverUrl: String,
    val deviceId: String,
    val key: String,
    val generation: Long,
    val intent: PushInstallationIntent,
    val acknowledged: Boolean = false,
    val receipt: PushDeviceRegisterResponse? = null,
    val refusal: String? = null,
    /**
     * This registration is uncertain and its login is being signed out (or its
     * server removed), so nobody will be left to replay it exactly: the next
     * registration supersedes it instead.
     */
    val ownerLeaving: Boolean = false,
    /**
     * The removal intent at this record's generation still has to reach the
     * server as a retirement, which needs only [key]: it is sent again after
     * the login that registered is gone, until the server answers for good.
     * Any newer generation supersedes it.
     */
    val retirement: PushRetirement? = null,
) { override fun toString() = "PushInstallation(<redacted>)" }

/** A queued retirement of [generation], first queued at [queuedAtMs] (epoch milliseconds). */
@Serializable
internal data class PushRetirement(val generation: Long, val queuedAtMs: Long)

@Serializable
internal data class PushInstallationIntent(
    val serverId: String,
    val loginId: String,
    val profileId: String,
    val profileToken: String?,
    /** null is the ordered removal verb. */
    val registration: PushDeviceRegisterRequest?,
) { override fun toString() = "PushInstallationIntent(<redacted>)" }

internal interface PushInstallationStore {
    suspend fun read(): List<PushInstallation>
    suspend fun write(records: List<PushInstallation>)
}

/** Private, non-backed-up installation proof and exact pending intent. Never reset on read errors. */
internal class FilePushInstallationStore(private val root: File) : PushInstallationStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "push-installations-v2"))
    private val lockFile by lazy {
        check(root.mkdirs() || root.isDirectory)
        RandomAccessFile(File(root, "owner.lock"), "rw")
    }
    private val ownerLock by lazy { requireNotNull(lockFile.channel.tryLock()) { "Push persistence already owned" } }
    private val marker = File(root, "initialized")
    private val file = AtomicFile(File(root, "installations.json"))
    override suspend fun read(): List<PushInstallation> = withContext(Dispatchers.IO) {
        check(ownerLock.isValid)
        if (!file.baseFile.exists() && !File(root, "installations.json.bak").exists()) {
            check(!marker.exists()) { "Push installation proof is missing" }
            emptyList()
        } else file.openRead().use { SiloJson.decodeFromString(it.readBytes().decodeToString()) }
    }
    override suspend fun write(records: List<PushInstallation>) = withContext(Dispatchers.IO + NonCancellable) {
        check(ownerLock.isValid)
        if (!marker.exists()) java.io.FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        val output = file.startWrite()
        try {
            output.write(SiloJson.encodeToString(records).encodeToByteArray())
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
