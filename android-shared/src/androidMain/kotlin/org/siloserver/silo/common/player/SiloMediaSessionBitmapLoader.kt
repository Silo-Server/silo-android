package org.siloserver.silo.common.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Loads Media3 notification artwork through Silo's process-wide Coil loader.
 *
 * Media3's default bitmap loader opens remote artwork with a bare
 * `HttpURLConnection`. That bypasses the image pipeline used everywhere else
 * in the app and can leave Android's media notification with a null large
 * icon even while the same backdrop renders in Compose. Reusing Coil gives
 * Now Playing the same network behavior and disk cache as the visible UI.
 */
@UnstableApi
class SiloMediaSessionBitmapLoader(context: Context) : BitmapLoader, Closeable {
    private val appContext = context.applicationContext
    private val imageLoader = appContext.imageLoader
    private val executor = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "silo-media-artwork").apply { isDaemon = true }
        },
    )

    override fun supportsMimeType(mimeType: String): Boolean =
        Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IOException("Silo media artwork data could not be decoded")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        runBlocking {
            val result = imageLoader.execute(
                ImageRequest.Builder(appContext)
                    .data(uri)
                    .size(MAX_ARTWORK_SIZE_PX, MAX_ARTWORK_SIZE_PX)
                    .build(),
            )
            if (result !is SuccessResult) {
                throw IOException("Silo media artwork could not be loaded")
            }
            val bitmap = result.image.toBitmap()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_ARTWORK_SIZE_PX = 1_024
    }
}
