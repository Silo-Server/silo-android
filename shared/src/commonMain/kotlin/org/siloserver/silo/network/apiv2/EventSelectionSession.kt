package org.siloserver.silo.network.apiv2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.network.*

/** A single socket's latest selection. Refusals leave its previously accepted selection intact. */
internal class EventSelectionSession(
    private val recovery: RealtimeBackoff,
    private var workAdmission: Boolean,
    private val nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
) {
    private sealed interface Input {
        data class Frame(val raw: String) : Input
        data class Desire(val channels: List<String>) : Input
        data class Retry(val id: String) : Input
        data class End(val failure: Throwable?) : Input
    }

    fun frames(
        initial: List<String>,
        receive: suspend () -> String?,
        transmit: suspend (String) -> Unit,
        current: suspend () -> Boolean,
        desiredSelections: Flow<List<String>> = emptyFlow(),
        onEnvelope: suspend (type: String?, errorCode: String?) -> Unit = { _, _ -> },
    ): Flow<String> = channelFlow {
        val inputs = Channel<Input>(Channel.BUFFERED)
        var desired = initial.toList()
        var sequence = 0L
        var requestId = "events-${++sequence}"
        var retry: Job? = null
        var retryId: String? = null
        var retryNotBeforeMs = 0L
        suspend fun subscribe() {
            if (current()) transmit(SiloJson.encodeToString(WsSubscribe.serializer(), WsSubscribe(requestId = requestId, channels = desired)))
        }
        val reader = launch {
            try {
                while (true) inputs.send(Input.Frame(receive() ?: break))
                inputs.send(Input.End(null))
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { inputs.send(Input.End(e)) }
        }
        val desires = launch { desiredSelections.collect { inputs.send(Input.Desire(it.toList())) } }
        try {
            // The URL has no declaration. Exactly one initial batch consumes the ticket credit.
            subscribe()
            for (input in inputs) {
                if (!current()) break
                when (input) {
                    is Input.Desire -> {
                        retry?.cancel()
                        retryId = null
                        desired = input.channels
                        requestId = "events-${++sequence}"
                        val wait = retryNotBeforeMs - nowMs()
                        if (wait > 0) {
                            val captured = requestId
                            retryId = captured
                            retry = launch { delay(wait); inputs.send(Input.Retry(captured)) }
                        } else subscribe()
                    }
                    is Input.Retry -> if (input.id == requestId && retryId == requestId) {
                        retryId = null
                        subscribe()
                    }
                    is Input.End -> { input.failure?.let { throw it }; break }
                    is Input.Frame -> {
                        val obj = runCatching { SiloJson.parseToJsonElement(input.raw) as? JsonObject }.getOrNull()
                        val type = (obj?.get("type") as? JsonPrimitive)?.content
                        val id = (obj?.get("request_id") as? JsonPrimitive)?.content
                        onEnvelope(type, if (type == "error") (obj?.get("code") as? JsonPrimitive)?.content else null)
                        if (type == "hello") {
                            val capability = obj["work_admission"]?.let {
                                runCatching { SiloJson.decodeFromJsonElement(RealtimeWorkAdmission.serializer(), it) }.getOrNull()
                            }
                            if (capability?.supported == true) workAdmission = true
                        }
                        if (type == "error" && (obj?.get("code") as? JsonPrimitive)?.content == "realtime_input_limited") {
                            recovery.markOverloaded()
                        }
                        if (type == "error" && workAdmission) {
                            val refusal = runCatching { SiloJson.decodeFromJsonElement(RealtimeRefusal.serializer(), obj!!) }.getOrNull()
                            if (refusal?.preWork == true && refusal.requestId == requestId) {
                                retry?.cancel()
                                val captured = requestId
                                val wait = recovery.refused(refusal.code, refusal.retryAfterMs!!, retainForAdmission = true)
                                retryNotBeforeMs = nowMs() + wait
                                retryId = captured
                                retry = launch { delay(wait); inputs.send(Input.Retry(captured)) }
                            }
                        }
                        if (type == "subscribed") {
                            if (workAdmission && id != requestId) continue
                            retry?.cancel()
                            retryId = null
                            recovery.useful()
                        }
                        if (type in setOf("snapshot", "event")) recovery.useful()
                        send(input.raw)
                    }
                }
            }
        } finally {
            retry?.cancel(); reader.cancel(); desires.cancel(); inputs.close()
        }
    }

    private companion object { val origin = kotlin.time.TimeSource.Monotonic.markNow() }
}
