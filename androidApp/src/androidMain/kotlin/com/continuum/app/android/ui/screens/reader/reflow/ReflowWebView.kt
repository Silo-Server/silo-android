package com.continuum.app.android.ui.screens.reader.reflow

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Thin wrapper around the reflow [WebView] exposing the JS paginator API.
 *
 * All strings are JSON-encoded via [JSONObject.quote] so HTML/CSS containing
 * quotes or newlines produces a safe JS string literal.
 */
class ReflowController(private val web: WebView) {
    fun load(html: String, baseUrl: String) {
        web.evaluateJavascript(
            "window.ReflowApi.load(${jsString(html)},${jsString(baseUrl)})",
            null,
        )
    }

    fun goToPage(n: Int) {
        web.evaluateJavascript("window.ReflowApi.goToPage($n)", null)
    }

    fun applyStyle(css: String) {
        web.evaluateJavascript("window.ReflowApi.applyStyle(${jsString(css)})", null)
    }

    private fun jsString(s: String): String = JSONObject.quote(s)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReflowWebView(
    modifier: Modifier,
    onTap: (Float) -> Unit,
    onScale: (Float) -> Unit,
    onEvent: (ReflowEvent) -> Unit,
    onCrash: () -> Unit,
    onReady: (ReflowController) -> Unit,
) {
    val context = LocalContext.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnScale by rememberUpdatedState(onScale)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnCrash by rememberUpdatedState(onCrash)
    val currentOnReady by rememberUpdatedState(onReady)

    // Create the WebView once and keep it stable across recompositions so the
    // page state survives. The bridge below posts back to the main thread.
    val webView = remember {
        WebView(context).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            // The shell page is loaded from android_asset, but EPUB CSS/images
            // live under the app-private extracted EPUB cache. Once paginator.js
            // installs the EPUB directory as <base>, these flags let relative
            // chapter resources render instead of falling back to broken alt text.
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
    }

    DisposableEffect(webView) {
        val mainHandler = Handler(Looper.getMainLooper())
        var readyDelivered = false
        val density = context.resources.displayMetrics.density
        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val width = webView.width.takeIf { it > 0 } ?: return false
                    currentOnTap((e.x / width).coerceIn(0f, 1f))
                    return true
                }
            },
        )
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentOnScale(detector.scaleFactor)
                    return true
                }
            },
        )

        // Push the WebView's real, Compose-measured size into the page as CSS px.
        // This is what gives every page a non-zero height; the WebView's own
        // viewport (`100vh`) latches to 0 because the page loads before layout.
        fun pushViewport() {
            val w = (webView.width / density).toInt()
            val h = (webView.height / density).toInt()
            if (w > 0 && h > 0) {
                webView.evaluateJavascript(
                    "window.ReflowApi&&window.ReflowApi.setViewport($w,$h)",
                    null,
                )
            }
        }

        val bridge = object {
            @JavascriptInterface
            fun onEvent(message: String) {
                val event = decodeReflowEvent(message) ?: return
                // JS interface callbacks arrive on a background thread; hop to main.
                mainHandler.post {
                    if (event is ReflowEvent.Ready && !readyDelivered) {
                        readyDelivered = true
                        currentOnReady(ReflowController(webView))
                    }
                    if (event is ReflowEvent.Ready) pushViewport()
                    currentOnEvent(event)
                }
            }
        }

        // Re-push whenever the view is (re)laid out, e.g. rotation or the very
        // first measure pass that happens after the page has already loaded.
        val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) pushViewport()
        }
        webView.addOnLayoutChangeListener(layoutListener)

        webView.addJavascriptInterface(bridge, "AndroidReflow")
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                currentOnCrash()
                return true
            }
        }
        webView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                tapDetector.onTouchEvent(event)
            }
            true
        }
        webView.loadUrl("file:///android_asset/reader/reflow/reader.html")

        onDispose {
            webView.setOnTouchListener(null)
            webView.removeOnLayoutChangeListener(layoutListener)
            webView.removeJavascriptInterface("AndroidReflow")
            webView.destroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}
