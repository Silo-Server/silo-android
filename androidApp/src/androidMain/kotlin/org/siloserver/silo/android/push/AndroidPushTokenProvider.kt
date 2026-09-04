package org.siloserver.silo.android.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.siloserver.silo.model.notifications.PushDeviceRegisterResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PushRegistrationRepository

interface AndroidPushTokenProvider {
    suspend fun token(): String?
}

class FirebaseAndroidPushTokenProvider(
    private val context: Context,
) : AndroidPushTokenProvider {
    override suspend fun token(): String? {
        val app = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
            ?: return null
        if (app.options.applicationId.isNullOrBlank()) return null
        return runCatching {
            FirebaseMessaging.getInstance().token.awaitOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

class AndroidPushRegistrar(
    private val tokenProvider: AndroidPushTokenProvider,
    private val repository: PushRegistrationRepository,
    private val deviceIdProvider: () -> String,
) {
    suspend fun registerIfAvailable(): ApiResult<PushDeviceRegisterResponse>? {
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return registerToken(token)
    }

    suspend fun registerToken(token: String): ApiResult<PushDeviceRegisterResponse> {
        return repository.registerAndroidDevice(
            token = token,
            deviceId = deviceIdProvider(),
        )
    }

    suspend fun unregisterDevice(): ApiResult<Unit> =
        repository.unregisterDevice(deviceIdProvider())
}

private suspend fun <T> Task<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
