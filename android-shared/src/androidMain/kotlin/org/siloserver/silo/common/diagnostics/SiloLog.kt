package org.siloserver.silo.common.diagnostics

import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.common.BuildConfig
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine

sealed interface SiloLogAttribute {
    data class Text(val value: String) : SiloLogAttribute
    data class Integer(val value: Long) : SiloLogAttribute
    data class Number(val value: Double) : SiloLogAttribute
    data class Flag(val value: Boolean) : SiloLogAttribute
    data class Url(val value: String) : SiloLogAttribute
    data class Failure(val value: Throwable) : SiloLogAttribute
}

fun interface DiagnosticsLogSink {
    fun offer(renderedJsonLine: String)
}

internal class DiagnosticsLogRenderer(
    private val redactor: DiagnosticsRedactor,
    private val strictAttributeRegistry: Boolean,
    private val runId: String = UUID.randomUUID().toString(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun render(
        level: DiagnosticsLogLevel,
        category: DiagnosticsLogCategory,
        tag: String,
        message: String,
        attributes: Map<String, SiloLogAttribute> = emptyMap(),
        throwable: Throwable? = null,
    ): String? {
        val safeTag = redactor.sanitize(tag).truncateUtf8(MAX_TAG_BYTES)
        val safeMessage = buildString {
            append(redactor.sanitize(message))
            throwable?.let {
                val failure = redactor.sanitizeThrowable(it)
                if (failure.isNotBlank()) {
                    append('\n')
                    append(failure)
                }
            }
        }.truncateUtf8(MAX_MESSAGE_BYTES)
        if (safeTag.isBlank() || safeMessage.isBlank()) return null

        val renderedAttributes = renderAttributes(category, attributes)
        val line = DiagnosticsLogLine(
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowEpochMillis())),
            run = runId.truncateUtf8(MAX_RUN_BYTES).ifBlank { "unknown-run" },
            level = level,
            category = category,
            tag = safeTag,
            message = safeMessage,
            attributes = renderedAttributes.ifEmpty { null },
        )
        return JSON.encodeToString(line)
    }

    private fun renderAttributes(
        category: DiagnosticsLogCategory,
        attributes: Map<String, SiloLogAttribute>,
    ): Map<String, JsonElement> {
        val registered = ATTRIBUTE_REGISTRY[category].orEmpty()
        return buildMap {
            attributes.forEach { (key, attribute) ->
                val expectedKind = registered[key]
                val actualKind = attribute.kind
                if (expectedKind == null || expectedKind != actualKind) {
                    if (strictAttributeRegistry) {
                        throw IllegalArgumentException(
                            "Unregistered or mismatched diagnostics attribute $category.$key: " +
                                "expected=$expectedKind actual=$actualKind",
                        )
                    }
                    return@forEach
                }

                val value = when (attribute) {
                    is SiloLogAttribute.Text -> JsonPrimitive(
                        redactor.sanitize(attribute.value).truncateUtf8(MAX_ATTRIBUTE_STRING_BYTES),
                    )
                    is SiloLogAttribute.Integer -> JsonPrimitive(attribute.value)
                    is SiloLogAttribute.Url -> JsonPrimitive(
                        redactor.sanitizeUrl(attribute.value).truncateUtf8(MAX_ATTRIBUTE_STRING_BYTES),
                    )
                    is SiloLogAttribute.Failure -> JsonPrimitive(
                        redactor.sanitizeThrowable(attribute.value).truncateUtf8(MAX_ATTRIBUTE_STRING_BYTES),
                    )
                    is SiloLogAttribute.Number -> JsonPrimitive(attribute.value)
                    is SiloLogAttribute.Flag -> JsonPrimitive(attribute.value)
                }
                put(key, value)
            }
        }
    }

    private val SiloLogAttribute.kind: AttributeKind
        get() = when (this) {
            is SiloLogAttribute.Text, is SiloLogAttribute.Url, is SiloLogAttribute.Failure -> AttributeKind.STRING
            is SiloLogAttribute.Integer -> AttributeKind.INTEGER
            is SiloLogAttribute.Number -> AttributeKind.NUMBER
            is SiloLogAttribute.Flag -> AttributeKind.BOOLEAN
        }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        val output = StringBuilder(length.coerceAtMost(maxBytes))
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val value = String(Character.toChars(codePoint))
            val valueBytes = value.encodeToByteArray().size
            if (usedBytes + valueBytes > maxBytes) break
            output.append(value)
            usedBytes += valueBytes
            index += Character.charCount(codePoint)
        }
        return output.toString()
    }

    private enum class AttributeKind { STRING, INTEGER, NUMBER, BOOLEAN }

    private companion object {
        const val MAX_TAG_BYTES = 128
        const val MAX_RUN_BYTES = 128
        const val MAX_MESSAGE_BYTES = 2_048
        const val MAX_ATTRIBUTE_STRING_BYTES = 512
        val JSON = Json { explicitNulls = false }
        val ATTRIBUTE_REGISTRY = mapOf(
            DiagnosticsLogCategory.PLAYBACK to mapOf(
                "sink" to AttributeKind.STRING,
                "fmt" to AttributeKind.STRING,
                "decoder" to AttributeKind.STRING,
                "width" to AttributeKind.INTEGER,
                "height" to AttributeKind.INTEGER,
                "hdr_mode" to AttributeKind.STRING,
                "bitrate_kbps" to AttributeKind.INTEGER,
                "dropped_frames" to AttributeKind.INTEGER,
                "audio_underruns" to AttributeKind.INTEGER,
                "failure_code" to AttributeKind.STRING,
                "startup_ready_ms" to AttributeKind.INTEGER,
                "first_frame_ms" to AttributeKind.INTEGER,
                "buffered_ms" to AttributeKind.INTEGER,
                "rebuffer_count" to AttributeKind.INTEGER,
                "rebuffer_total_ms" to AttributeKind.INTEGER,
                "rebuffer_max_ms" to AttributeKind.INTEGER,
                "seek_count" to AttributeKind.INTEGER,
                "seek_last_ms" to AttributeKind.INTEGER,
                "seek_total_ms" to AttributeKind.INTEGER,
                "seek_max_ms" to AttributeKind.INTEGER,
            ),
            DiagnosticsLogCategory.FOCUS to mapOf(
                "target" to AttributeKind.STRING,
                "action" to AttributeKind.STRING,
            ),
            DiagnosticsLogCategory.NETWORK to mapOf(
                "method" to AttributeKind.STRING,
                "path" to AttributeKind.STRING,
                "status" to AttributeKind.INTEGER,
                "duration_ms" to AttributeKind.INTEGER,
            ),
            DiagnosticsLogCategory.LIFECYCLE to mapOf(
                "state" to AttributeKind.STRING,
                "route" to AttributeKind.STRING,
                "frame_count" to AttributeKind.INTEGER,
                "slow_frame_count" to AttributeKind.INTEGER,
                "p95_frame_ms" to AttributeKind.INTEGER,
                "worst_frame_ms" to AttributeKind.INTEGER,
                "main_stall_count" to AttributeKind.INTEGER,
                "main_stall_max_ms" to AttributeKind.INTEGER,
                "java_heap_mb" to AttributeKind.INTEGER,
                "process_pss_mb" to AttributeKind.INTEGER,
                "low_memory" to AttributeKind.BOOLEAN,
                "thermal_status" to AttributeKind.INTEGER,
                "startup_first_frame_ms" to AttributeKind.INTEGER,
            ),
            DiagnosticsLogCategory.CRASH to mapOf(
                "fingerprint" to AttributeKind.STRING,
                "source" to AttributeKind.STRING,
            ),
        )
    }
}

object SiloLog {
    /** Stable app-run id shared by log lines and diagnostics manifests. */
    val captureSessionId: String = "run_" + UUID.randomUUID().toString().replace("-", "")

    private val sink = AtomicReference<DiagnosticsLogSink?>()
    private val breadcrumbSink = AtomicReference<DiagnosticsLogSink?>()
    private val defaultRenderer = DiagnosticsLogRenderer(
        redactor = DiagnosticsRedactor(),
        strictAttributeRegistry = BuildConfig.DEBUG,
        runId = captureSessionId,
    )
    private val renderer = AtomicReference(defaultRenderer)

    fun installSink(value: DiagnosticsLogSink?) {
        sink.set(value)
    }

    fun installBreadcrumbSink(value: DiagnosticsLogSink?) {
        breadcrumbSink.set(value)
    }

    internal fun installRenderer(value: DiagnosticsLogRenderer) {
        renderer.set(value)
    }

    internal fun setRendererForTests(value: DiagnosticsLogRenderer) = installRenderer(value)

    internal fun resetRendererForTests() {
        renderer.set(defaultRenderer)
    }

    fun v(category: DiagnosticsLogCategory, tag: String, message: String, attributes: Map<String, SiloLogAttribute> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticsLogLevel.VERBOSE, category, tag, message, attributes, throwable)

    fun d(category: DiagnosticsLogCategory, tag: String, message: String, attributes: Map<String, SiloLogAttribute> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticsLogLevel.DEBUG, category, tag, message, attributes, throwable)

    fun i(category: DiagnosticsLogCategory, tag: String, message: String, attributes: Map<String, SiloLogAttribute> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticsLogLevel.INFO, category, tag, message, attributes, throwable)

    fun w(category: DiagnosticsLogCategory, tag: String, message: String, attributes: Map<String, SiloLogAttribute> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticsLogLevel.WARNING, category, tag, message, attributes, throwable)

    fun e(category: DiagnosticsLogCategory, tag: String, message: String, attributes: Map<String, SiloLogAttribute> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticsLogLevel.ERROR, category, tag, message, attributes, throwable)

    /** Curated context retained across process death for ANR/native-crash assembly. */
    fun breadcrumb(
        category: DiagnosticsLogCategory,
        tag: String,
        message: String,
        attributes: Map<String, SiloLogAttribute> = emptyMap(),
    ) = write(DiagnosticsLogLevel.INFO, category, tag, message, attributes, null, breadcrumb = true)

    private fun write(
        level: DiagnosticsLogLevel,
        category: DiagnosticsLogCategory,
        tag: String,
        message: String,
        attributes: Map<String, SiloLogAttribute>,
        throwable: Throwable?,
        breadcrumb: Boolean = false,
    ) {
        when (level) {
            DiagnosticsLogLevel.VERBOSE -> if (throwable == null) Log.v(tag, message) else Log.v(tag, message, throwable)
            DiagnosticsLogLevel.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            DiagnosticsLogLevel.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            DiagnosticsLogLevel.WARNING -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            DiagnosticsLogLevel.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
        val target = sink.get()
        val breadcrumbTarget = breadcrumbSink.get().takeIf { breadcrumb }
        if (target == null && breadcrumbTarget == null) return
        renderer.get().render(level, category, tag, message, attributes, throwable)?.let { rendered ->
            target?.let { runCatching { it.offer(rendered) } }
            breadcrumbTarget?.let { runCatching { it.offer(rendered) } }
        }
    }
}
