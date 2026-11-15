package org.siloserver.silo.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.Serializable
import org.siloserver.silo.common.i18n.UiText

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    /**
     * Screens show [errorText][org.siloserver.silo.common.i18n.errorText].
     *
     * - [message]: for a v2 problem, the server's `detail`, translated when the
     *   interface isn't English (see [org.siloserver.silo.network.apiv2.ProblemText]);
     *   for an error the app builds, its English, for logs.
     * - [detail]: the server's own text, for code that must recognise it.
     * - [text]: what a screen shows for an error the app builds; null for the
     *   server's errors. Code that passes an error on returns the same object,
     *   so this survives.
     *
     * Code recognises an error by [code] and [error], never by [message].
     */
    data class Error(
        val code: Int,
        val error: String,
        val message: String,
        val detail: String = message,
        val text: UiText? = null,
    ) : ApiResult<Nothing>()
    data class NetworkError(val exception: Throwable) : ApiResult<Nothing>()
}

@Serializable
data class ApiErrorBody(
    val error: String = "",
    val message: String = ""
)

fun <T> ApiResult<T>.getOrNull(): T? = when (this) {
    is ApiResult.Success -> data
    else -> null
}

fun <T> ApiResult<T>.getOrThrow(): T = when (this) {
    is ApiResult.Success -> data
    is ApiResult.Error -> throw RuntimeException("API Error $code: $message")
    is ApiResult.NetworkError -> throw exception
}

suspend fun <T, R> ApiResult<T>.map(transform: suspend (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Error -> this
    is ApiResult.NetworkError -> this
}

/** English for failures that never reached the server (screens use `errorText`). */
const val NETWORK_ERROR_MESSAGE = "Network error. Check your connection."

/** English for a request the server accepted but did not answer in time (screens use `errorText`). */
const val REQUEST_TIMEOUT_MESSAGE = "The server took too long to respond. Try again."

/**
 * Whether this failure is a timeout rather than an unreachable server. The
 * distinction matters for the copy: "check your connection" is wrong advice
 * when the connection is fine and the server is merely slow.
 */
val ApiResult.NetworkError.isTimeout: Boolean
    get() = generateSequence(exception) { it.cause?.takeIf { c -> c !== it } }
        .any {
            it is HttpRequestTimeoutException ||
                it is SocketTimeoutException ||
                it is ConnectTimeoutException
        }

/**
 * English error text for a failed [ApiResult], for logs; screens use
 * [errorText][org.siloserver.silo.common.i18n.errorText]. The server-provided
 * message when present, [fallback] when it is blank, and the standard
 * network-error copy for [ApiResult.NetworkError]. Total over the sealed
 * type ([fallback] for Success) so it can be called on the when-subject
 * inside a merged `is Error, is NetworkError ->` branch.
 */
fun ApiResult<*>.errorMessage(fallback: String): String = when (this) {
    is ApiResult.Success -> fallback
    is ApiResult.Error -> message.ifBlank { fallback }
    is ApiResult.NetworkError -> if (isTimeout) REQUEST_TIMEOUT_MESSAGE else NETWORK_ERROR_MESSAGE
}
