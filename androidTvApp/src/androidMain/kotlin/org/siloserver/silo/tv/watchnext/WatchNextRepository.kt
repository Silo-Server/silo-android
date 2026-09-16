package org.siloserver.silo.tv.watchnext

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@SuppressLint("RestrictedApi")
class WatchNextRepository(private val context: Context) {
    internal val writeGate = WatchNextWriteGate()

    fun invalidate() = writeGate.invalidate()

    suspend fun diffAndApply(
        remoteFields: List<WatchNextProgramFields>,
        run: Long,
        authority: suspend () -> Boolean,
    ): DiffResult? =
        withContext(Dispatchers.IO) {
            writeGate.write(run, authority) {
                val resolver = context.contentResolver
                val existing = readOurExistingPrograms()
                // Collapse duplicate externalIds up front: without this, two remote
                // rows sharing an externalId both hit the insert branch (measured
                // against the same pre-loop snapshot) and the second becomes an
                // orphan the delete pass never reaches.
                val remote = remoteFields.distinctBy { it.externalId }
                val remoteByExternalId = remote.associateBy { it.externalId }

                var inserted = 0
                var updated = 0
                var deleted = 0

                for (fields in remote) {
                    // Cooperative cancellation: a cancelled worker (profile switch /
                    // sign-out) stops before writing so it can't repopulate the
                    // previous profile's tiles into the shared launcher provider.
                    coroutineContext.ensureActive()
                    if (!writeGate.allowed(run, authority)) return@write null
                    val existingId = existing[fields.externalId]
                    if (existingId == null) {
                        val uri = resolver.insert(
                            TvContractCompat.WatchNextPrograms.CONTENT_URI,
                            fields.toContentValues(),
                        )
                        if (uri != null) inserted++
                    } else {
                        val rowUri = ContentUris.withAppendedId(
                            TvContractCompat.WatchNextPrograms.CONTENT_URI,
                            existingId,
                        )
                        val rows = resolver.update(rowUri, fields.toContentValues(), null, null)
                        if (rows > 0) updated++
                    }
                }

                for ((externalId, rowId) in existing) {
                    coroutineContext.ensureActive()
                    if (!writeGate.allowed(run, authority)) return@write null
                    if (externalId !in remoteByExternalId) {
                        val rowUri = ContentUris.withAppendedId(
                            TvContractCompat.WatchNextPrograms.CONTENT_URI,
                            rowId,
                        )
                        if (resolver.delete(rowUri, null, null) > 0) deleted++
                    }
                }

                DiffResult(inserted = inserted, updated = updated, deleted = deleted)
            }
        }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writeGate.clear {
            val resolver = context.contentResolver
            for ((_, rowId) in readOurExistingPrograms()) {
                val rowUri = ContentUris.withAppendedId(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    rowId,
                )
                resolver.delete(rowUri, null, null)
            }
        }
    }

    private fun readOurExistingPrograms(): Map<String, Long> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            TvContractCompat.WatchNextPrograms._ID,
            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
        )
        val result = mutableMapOf<String, Long>()
        resolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms._ID)
            val extIdx = cursor.getColumnIndexOrThrow(
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )
            while (cursor.moveToNext()) {
                val rowId = cursor.getLong(idIdx)
                val externalId = cursor.getString(extIdx) ?: continue
                result[externalId] = rowId
            }
        }
        return result
    }

    private fun WatchNextProgramFields.toContentValues(): ContentValues =
        WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(watchNextType)
            .setTitle(title)
            .setPosterArtUri(Uri.parse(posterArtUri))
            .setPosterArtAspectRatio(posterArtAspectRatio)
            // Only stamp when we have a real engagement time; omitting the
            // column leaves any existing provider value untouched on update.
            .apply { lastEngagementTimeMs?.let { setLastEngagementTimeUtcMillis(it) } }
            .setIntentUri(Uri.parse(intentUri))
            .setInternalProviderId(externalId)
            .build()
            .toContentValues()

    data class DiffResult(val inserted: Int, val updated: Int, val deleted: Int)
}
