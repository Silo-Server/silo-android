package org.siloserver.silo.common.images

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.player.SiloMediaSessionBitmapLoader
import org.siloserver.silo.network.resolveArtworkUrl
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArtworkLoadingTest {
    @Test
    fun phoneLoadsSignedAndDirectArtworkWithoutLoginHeaders() = verifyArtworkLoading()

    @Test
    @Config(sdk = [28], qualifiers = "television")
    fun tvLoadsSignedAndDirectArtworkWithoutLoginHeaders() = verifyArtworkLoading()

    private fun verifyArtworkLoading() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = java.nio.file.Files.createTempDirectory("artwork-test").toFile()
        val loader = buildSiloImageLoader(context, cache)
        SingletonImageLoader.setUnsafe(loader)
        val local = MockWebServer()
        val direct = MockWebServer()
        local.start()
        direct.start()
        val png = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        fun response() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
        try {
            val signed = "/api/v2/artwork/a%2Fb.png?exp=123&sig=a%2Bb%2F%3D"
            local.enqueue(response())
            val resolved = resolveArtworkUrl(signed, local.url("/base").toString())
            assertIs<SuccessResult>(loader.execute(ImageRequest.Builder(context).data(resolved).build()))
            val localRequest = assertNotNull(local.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(signed, localRequest.path)
            listOf("Authorization", "X-Profile-Id", "X-Profile-Token", "Cookie").forEach { assertNull(localRequest.getHeader(it)) }

            direct.enqueue(response())
            val otherServer = resolveArtworkUrl(signed, direct.url("/base").toString())
            assertIs<SuccessResult>(loader.execute(ImageRequest.Builder(context).data(otherServer).build()))
            assertEquals(signed, assertNotNull(direct.takeRequest(5, TimeUnit.SECONDS)).path)

            // Direct storage URLs travel through the same loader, including the
            // media-session path used by phone playback and TV playback.
            direct.enqueue(response())
            val external = direct.url("/bucket/backdrop.png?X-Amz-Signature=a%2Bb").toString()
            assertEquals(external, resolveArtworkUrl(external, local.url("/").toString()))
            SiloMediaSessionBitmapLoader(context).use { media ->
                val bitmap = media.loadBitmap(Uri.parse(external)).get(10, TimeUnit.SECONDS)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
            }
            val externalRequest = assertNotNull(direct.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/bucket/backdrop.png?X-Amz-Signature=a%2Bb", externalRequest.path)
            listOf("Authorization", "X-Profile-Id", "X-Profile-Token", "Cookie").forEach { assertNull(externalRequest.getHeader(it)) }
        } finally {
            loader.shutdown()
            SingletonImageLoader.reset()
            local.shutdown()
            direct.shutdown()
            cache.deleteRecursively()
        }
    }
}
