package org.siloserver.silo.network.apiv2

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson

/**
 * Wraps one v2 exchange: a 2xx body decodes to [T] with the production
 * [SiloJson]; a non-2xx body is read as a [Problem] and surfaced as
 * [ApiResult.Error] (`error` = the problem code, `message` = its detail).
 * Nothing here retries, and nothing here knows a v1 path.
 */
internal suspend inline fun <reified T> safeApiV2Call(
    gate: ApiV2Gate,
    block: () -> HttpResponse,
): ApiResult<T> {
    gate.blocked()?.let { return it }
    return try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(SiloJson.decodeFromString(response.bodyAsText()))
        } else {
            response.toApiV2Error()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}

internal suspend fun HttpResponse.toApiV2Error(): ApiResult.Error {
    val problem = try {
        SiloJson.decodeFromString(Problem.serializer(), bodyAsText())
    } catch (_: Exception) {
        null
    }
    return ApiResult.Error(
        code = status.value,
        error = problem?.code ?: "",
        message = problem?.detail ?: "",
    )
}
