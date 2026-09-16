package org.siloserver.silo.common.player

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.PlaybackJournalEntry
import org.siloserver.silo.repository.PlaybackJournalStore

/** App-private, excluded from backup. A second process must not replay this journal. */
class AndroidPlaybackJournalStore(context: Context) : PlaybackJournalStore {
    private val directory by lazy { File(context.noBackupFilesDir, "playback-v2").apply { check(mkdirs() || isDirectory) } }
    private val lockFile by lazy { RandomAccessFile(File(directory, "owner.lock"), "rw") }
    // Retained for the lifetime of the singleton; OS releases it on process death.
    private val ownerLock by lazy { requireNotNull(lockFile.channel.tryLock()) { "Playback journal has another process owner" } }
    private val file by lazy { AtomicFile(File(directory, "requests.json")) }

    override suspend fun read(): List<PlaybackJournalEntry> = withContext(Dispatchers.IO) {
        check(ownerLock.isValid)
        if (!file.baseFile.exists() && !File(directory, "requests.json.bak").exists()) emptyList()
        else file.openRead().use { SiloJson.decodeFromString(it.readBytes().decodeToString()) }
    }

    override suspend fun write(entries: List<PlaybackJournalEntry>) = withContext(Dispatchers.IO) {
        check(ownerLock.isValid)
        val bytes = SiloJson.encodeToString(entries).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
