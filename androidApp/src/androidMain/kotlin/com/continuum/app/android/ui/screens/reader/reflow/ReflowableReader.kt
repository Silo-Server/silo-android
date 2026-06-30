package com.continuum.app.android.ui.screens.reader.reflow

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.screens.reader.resolveReaderFile
import com.continuum.app.common.ebook.ReaderDisplaySettings
import com.continuum.app.common.ebook.ReaderSection
import com.continuum.app.model.book.BookFormat
import com.continuum.app.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/**
 * Orchestrates a reflowable-reader session: resolves the source file, builds a
 * [ReflowableSource], drives the [ReflowWebView] paginator, persists locators,
 * and maps tap-zones / pinch-zoom gestures onto page turns + text-scale nudges.
 *
 * Sections are loaded lazily (one at a time) so unseen sections are never
 * pre-rendered; book-level progress is estimated from per-section text lengths
 * via [SectionWeights]. Restore happens by re-paginating the saved section and
 * seeking to the saved page progression once the WebView reports its page count.
 */
@Composable
fun ReflowableReader(
    format: BookFormat,
    fileUrl: String,
    settings: ReaderDisplaySettings,
    initialLocator: String?,
    onLocatorChanged: (locationJson: String, progress: Double) -> Unit,
    onSectionsKnown: (List<ReaderSection>) -> Unit,
    onTextScaleNudge: (Float) -> Unit,
    jumpToLocation: String?,
    onJumpConsumed: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val okHttp = koinInject<OkHttpClient>()
    val tokenManager = koinInject<TokenManager>()
    val systemDark = isSystemInDarkTheme()

    val sourceResult by produceState<Result<ReflowableSource>?>(null, fileUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = resolveReaderFile(
                    context,
                    okHttp,
                    fileUrl,
                    tokenManager.getServerUrl(),
                    extension = format.wire,
                )
                buildReflowableSource(format, file, context.cacheDir)
            }
        }
    }

    val result = sourceResult
    if (result == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .toggleChromeOnTap(onToggleChrome),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    result.exceptionOrNull()?.let { throwable ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .toggleChromeOnTap(onToggleChrome),
            contentAlignment = Alignment.Center,
        ) {
            Text(throwable.message ?: "Could not open this book.", modifier = Modifier.padding(32.dp))
        }
        return
    }
    val source = result.getOrThrow()

    val weights = remember(source) { SectionWeights(source.sections.map { it.approxChars }) }

    LaunchedEffect(source) {
        onSectionsKnown(
            source.tableOfContents.map {
                ReaderSection(
                    index = it.sectionIndex,
                    title = it.title,
                    location = ReflowLocatorCodec.encode(
                        ReflowLocator(
                            it.sectionIndex,
                            0.0,
                            weights.bookProgression(it.sectionIndex, 0.0),
                        ),
                    ),
                )
            },
        )
    }

    val initialReflowLocator = remember(source) {
        ReflowLocatorCodec.decode(initialLocator)?.coerceForSectionCount(source.sections.size)
    }
    var sectionIndex by remember(source) {
        mutableStateOf(initialReflowLocator?.sectionIndex ?: 0)
    }
    var pendingPageProgression by remember(source) {
        mutableStateOf(initialReflowLocator?.pageProgression ?: 0.0)
    }
    var relocationGate by remember(source) {
        mutableStateOf(ReflowInitialRelocationGate(pendingPageProgression))
    }
    var pageCount by remember(source) { mutableStateOf(1) }
    var page by remember(source) { mutableStateOf(0) }
    var controller by remember(source) { mutableStateOf<ReflowController?>(null) }
    var webViewResetKey by remember(source) { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val loadSection: () -> Unit = {
        scope.launch {
            val html = source.html(sectionIndex) ?: "<p>This section could not be loaded.</p>"
            controller?.load(html, source.baseUrl(sectionIndex))
            controller?.applyStyle(settings.toReflowStyle(systemDark).toCss())
        }
    }

    // Drives the initial load (once the WebView is ready) and every section change.
    LaunchedEffect(sectionIndex, controller, source) {
        if (controller != null) loadSection()
    }

    // Apply an external TOC / bookmark jump by seeking to the decoded locator.
    // The effect on [sectionIndex] reloads the section; [pendingPageProgression]
    // is applied on the next `paginated` event.
    LaunchedEffect(jumpToLocation) {
        if (jumpToLocation != null) {
            ReflowLocatorCodec.decode(jumpToLocation)?.coerceForSectionCount(source.sections.size)?.let {
                sectionIndex = it.sectionIndex
                pendingPageProgression = it.pageProgression
                relocationGate = ReflowInitialRelocationGate(it.pageProgression)
            }
            onJumpConsumed()
        }
    }

    // Re-style live when display settings change.
    LaunchedEffect(settings, controller) {
        controller?.applyStyle(settings.toReflowStyle(systemDark).toCss())
    }

    val nextPage: () -> Unit = {
        if (page < pageCount - 1) {
            controller?.goToPage(page + 1)
        } else if (sectionIndex < source.sections.lastIndex) {
            pendingPageProgression = 0.0
            relocationGate = ReflowInitialRelocationGate(0.0)
            sectionIndex++
        }
    }
    val prevPage: () -> Unit = {
        if (page > 0) {
            controller?.goToPage(page - 1)
        } else if (sectionIndex > 0) {
            pendingPageProgression = 1.0
            relocationGate = ReflowInitialRelocationGate(1.0)
            sectionIndex--
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        key(webViewResetKey) {
            ReflowWebView(
                modifier = Modifier.fillMaxSize(),
                onTap = { xFraction ->
                    when {
                        xFraction < 1f / 3f -> prevPage()
                        xFraction > 2f / 3f -> nextPage()
                        else -> onToggleChrome()
                    }
                },
                onScale = onTextScaleNudge,
                onReady = { c -> controller = c },
                onCrash = {
                    controller = null
                    webViewResetKey += 1
                },
                onEvent = { ev ->
                    when (ev) {
                        is ReflowEvent.Paginated -> {
                            pageCount = ev.pageCount.coerceAtLeast(1)
                            relocationGate.consumeInitialPageTarget(pageCount)?.let { target ->
                                controller?.goToPage(target)
                            }
                            pendingPageProgression = 0.0
                        }
                        is ReflowEvent.Relocated -> {
                            page = ev.page
                            if (!relocationGate.shouldPersistRelocation(ev.page)) return@ReflowWebView
                            val bp = weights.bookProgression(sectionIndex, ev.pageProgression)
                            onLocatorChanged(
                                ReflowLocatorCodec.encode(
                                    ReflowLocator(sectionIndex, ev.pageProgression, bp),
                                ),
                                bp,
                            )
                        }
                        ReflowEvent.Ready, is ReflowEvent.Error -> {}
                    }
                },
            )
        }
    }
}

private fun Modifier.toggleChromeOnTap(onToggleChrome: () -> Unit): Modifier =
    pointerInput(onToggleChrome) {
        detectTapGestures(onTap = { onToggleChrome() })
    }
