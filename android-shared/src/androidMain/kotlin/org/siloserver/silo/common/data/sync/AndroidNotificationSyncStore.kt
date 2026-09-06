package org.siloserver.silo.common.data.sync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.NotificationSyncStore

class AndroidNotificationSyncStore(context: Context) : NotificationSyncStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "notification-sync-v2.json"))
    private val mutex = Mutex()
    private fun readAll(): Map<String, String> = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyMap()
        else file.openRead().use { SiloJson.decodeFromString(it.readBytes().decodeToString()) }
    override suspend fun read(key: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) { readAll()[key] }
    }
    override suspend fun write(key: String, cursor: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = SiloJson.encodeToString(readAll() + (key to cursor)).encodeToByteArray()
            val output = file.startWrite()
            try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
        }
    }
}
