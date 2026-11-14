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

private const val BINDING_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

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
    /**
     * This record's generation was allocated for a server that advertises recording
     * the caller's login session, which it does only when it applies a new generation,
     * never on a replay. False on a record an older app version wrote or one allocated
     * for a server that does not advertise it: its registration may have no login
     * session there, so it gets one newer generation once the server advertises it.
     */
    val loginSessionBound: Boolean = false,
    /** When a saved server that is not active was last asked about binding. */
    val bindingCheckedAtMs: Long = 0,
) {
    /**
     * A settled registration still without [loginSessionBound]. Never one the server
     * refused, one being removed, or one whose login is on its way out.
     */
    val needsLoginSessionBinding: Boolean
        get() = !loginSessionBound && acknowledged && refusal == null && !ownerLeaving && intent.registration != null

    /** At most daily, whatever the last answer was; a clock set back only asks sooner. */
    fun bindingCheckDue(nowMs: Long): Boolean =
        needsLoginSessionBinding && bindingCheckedAtMs !in (nowMs - BINDING_CHECK_INTERVAL_MS + 1)..nowMs

    /**
     * A rebinding not yet confirmed, the only pending intent that keeps a [receipt]:
     * the server confirmed the same registration before it, so it changes nothing the
     * server delivers whether or not it landed.
     */
    val rebindingUnconfirmed: Boolean
        get() = !acknowledged && receipt != null && intent.registration != null

    /** The server delivers to this registration: it confirmed it, or the same one before a rebinding. */
    val deliversPushes: Boolean
        get() = refusal == null && intent.registration != null && (acknowledged || rebindingUnconfirmed)

    override fun toString() = "PushInstallation(<redacted>)"
}

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
    /**
     * The saved account on [serverId] that holds this installation; null on
     * records written before accounts, which the next registration replaces.
     */
    val accountId: String? = null,
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
