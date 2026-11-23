package org.siloserver.silo.common.player.trickplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.network.SiloOkHttp
import org.siloserver.silo.playback.trickplay.TrickplayManifest
import org.siloserver.silo.playback.trickplay.TrickplayManifestSource
import org.siloserver.silo.playback.trickplay.TrickplayPreviews
import org.siloserver.silo.playback.trickplay.TrickplaySheet
import org.siloserver.silo.playback.trickplay.TrickplaySheetSource
import org.siloserver.silo.playback.trickplay.TrickplayTarget
import org.siloserver.silo.playback.trickplay.TrickplayTile
import org.siloserver.silo.playback.trickplay.TrickplayTileDecoder
import java.io.IOException

/**
 * The seek-bar thumbnails for a player: sheets download through the
 * artwork client, which carries no Silo credentials (each sheet URL
 * authorizes itself, and a presigned storage URL refuses a second
 * credential), and thumbnails are cut with a region decoder so a sheet is
 * never decoded whole. Call [TrickplayPreviews.bind] and
 * [TrickplayPreviews.show] from the main thread.
 */
fun trickplayPreviews(manifests: TrickplayManifestSource, scope: CoroutineScope): TrickplayPreviews<ImageBitmap> =
    TrickplayPreviews(
        manifests = manifests,
        sheets = OkHttpTrickplaySheets(SiloOkHttp.imageClient),
        decoder = RegionTrickplayDecoder(),
        scope = scope,
        now = System::currentTimeMillis,
    )

/**
 * The file to show previews for: the version being streamed, when the server
 * publishes previews for it. Offline and local playback have no session and
 * show the time only.
 */
fun trickplayTarget(contentId: String, fileId: Int?, versions: List<FileVersion>, streaming: Boolean): TrickplayTarget? {
    if (!streaming || contentId.isBlank() || fileId == null) return null
    return versions.firstOrNull { it.fileId == fileId }?.takeIf { it.trickplayAvailable }?.let { TrickplayTarget(contentId, fileId) }
}

internal class OkHttpTrickplaySheets(private val client: OkHttpClient) : TrickplaySheetSource {
    override suspend fun sheet(url: String): TrickplaySheet = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        // Bounded by what arrives, not only by Content-Length,
                        // which a chunked response does not send.
                        val source = response.body?.takeIf { it.contentLength() <= MAX_SHEET_BYTES }?.source()
                        if (source == null || source.request(MAX_SHEET_BYTES + 1)) TrickplaySheet.Failed
                        else TrickplaySheet.Loaded(source.readByteArray())
                    }
                    // A signature that expired or no longer names a published
                    // revision; the server answers 404 for a changed one.
                    response.code in REFUSED -> TrickplaySheet.Refused
                    else -> TrickplaySheet.Failed
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Seek-preview sheet unavailable: ${e.javaClass.simpleName}")
            TrickplaySheet.Failed
        } catch (e: IllegalArgumentException) {
            TrickplaySheet.Failed
        }
    }

    private companion object {
        val REFUSED = setOf(401, 403, 404, 410)
        /** A sheet is a few hundred kilobytes; anything far larger is not one. */
        const val MAX_SHEET_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Cuts thumbnails with [BitmapRegionDecoder], keeping the decoder for the
 * last sheet used, so scrubbing across one sheet parses it once.
 */
internal class RegionTrickplayDecoder : TrickplayTileDecoder<ImageBitmap> {
    private val lock = Mutex()
    private var source: ByteArray? = null
    private var decoder: BitmapRegionDecoder? = null

    override suspend fun decode(sheet: ByteArray, tile: TrickplayTile, manifest: TrickplayManifest): ImageBitmap? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val region = decoderFor(sheet) ?: return@withLock null
                // A sheet is laid out at the manifest's size; scale the cut
                // when the image is not.
                val scale = region.width.toDouble() / (manifest.columns * manifest.thumbnailWidth)
                val rect = Rect(
                    (tile.x * scale).toInt(),
                    (tile.y * scale).toInt(),
                    ((tile.x + tile.width) * scale).toInt().coerceAtMost(region.width),
                    ((tile.y + tile.height) * scale).toInt().coerceAtMost(region.height),
                )
                if (rect.isEmpty) return@withLock null
                runCatching {
                    region.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }.getOrNull()?.asImageBitmap()
            }
        }

    private fun decoderFor(sheet: ByteArray): BitmapRegionDecoder? {
        if (sheet === source) return decoder
        decoder?.recycle()
        decoder = null
        source = sheet
        decoder = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(sheet, 0, sheet.size)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(sheet, 0, sheet.size, false)
            }
        }.getOrNull()
        return decoder
    }
}

private const val TAG = "Trickplay"
