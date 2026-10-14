package org.siloserver.silo.common.images

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedDownloadInterceptorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var loader: ImageLoader

    @Before
    fun setUp() {
        server.start()
        loader = buildSiloImageLoader(context, java.nio.file.Files.createTempDirectory("shared-download").toFile())
    }

    @After
    fun tearDown() {
        loader.shutdown()
        server.shutdown()
    }

    @Test
    fun concurrentRequestsForOneUrlShareADownload() = runBlocking<Unit> {
        server.enqueue(slowPng())
        val url = server.url("/backdrop.png").toString()

        // The detail page's prefetch, hero and blurred surface: same URL, different sizes.
        val results = listOf(1080, 640, 256).map { size ->
            async { loader.execute(ImageRequest.Builder(context).data(url).size(size).build()) }
        }.awaitAll()

        results.forEach { assertIs<SuccessResult>(it) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun waiterStillLoadsWhenTheFirstRequestIsCancelled() = runBlocking<Unit> {
        server.enqueue(slowPng())
        server.enqueue(slowPng())
        val url = server.url("/backdrop.png").toString()

        val first = async { loader.execute(ImageRequest.Builder(context).data(url).size(1080).build()) }
        withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
        val second = async { loader.execute(ImageRequest.Builder(context).data(url).size(256).build()) }
        delay(100)
        first.cancel()

        assertIs<SuccessResult>(withTimeout(5_000) { second.await() })
    }

    @Test
    fun waiterDownloadsItselfWhenTheFirstRequestFails() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500).setBodyDelay(400, TimeUnit.MILLISECONDS))
        server.enqueue(slowPng())
        val url = server.url("/backdrop.png").toString()

        val first = async { loader.execute(ImageRequest.Builder(context).data(url).size(1080).build()) }
        withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
        val second = async { loader.execute(ImageRequest.Builder(context).data(url).size(256).build()) }

        assertIs<ErrorResult>(first.await())
        assertIs<SuccessResult>(second.await())
        assertEquals(2, server.requestCount)
    }

    private fun slowPng(): MockResponse {
        val png = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        return MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(png))
            .setBodyDelay(400, TimeUnit.MILLISECONDS)
    }
}
