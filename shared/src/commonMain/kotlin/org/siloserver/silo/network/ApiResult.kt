package org.siloserver.silo.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.Serializable

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val error: String, val message: String) : ApiResult<Nothing>()
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

/** Standard copy for failures that never reached the server. */
const val NETWORK_ERROR_MESSAGE = "Network error. Check your connection."

/** Copy for a request the server accepted but did not answer in time. */
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
 * User-facing error text for a failed [ApiResult]: the server-provided
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
