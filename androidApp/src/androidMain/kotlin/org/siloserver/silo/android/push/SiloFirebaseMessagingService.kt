package org.siloserver.silo.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

class PushMessageHandler(
    private val presenter: PushNotificationPresenter,
) {
    suspend fun handle(
        data: Map<String, String>,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ): Boolean {
        val deliveryId = deliveryIdFrom(data) ?: return false
        // The relay's background_wake mode is a silent sync signal: refresh the
        // inbox so content is ready, but never surface a visible notification.
        if (data[MODE_KEY]?.trim() == MODE_BACKGROUND_WAKE) {
            presenter.sync()
            return true
        }
        presenter.present(
            deliveryId = deliveryId,
            fallbackTitle = fallbackTitle ?: data["title"],
            fallbackBody = fallbackBody ?: data["body"],
        )
        return true
    }

    private fun deliveryIdFrom(data: Map<String, String>): String? =
        DELIVERY_ID_KEYS.firstNotNullOfOrNull { key ->
            data[key]?.trim()?.takeIf { it.isNotEmpty() }
        }

    companion object {
        // The Silo push relay sends data-only messages carrying exactly
        // silo_delivery_id and silo_mode; the legacy keys stay as fallbacks.
        const val SILO_DELIVERY_ID_KEY = "silo_delivery_id"
        const val DELIVERY_ID_KEY = "delivery_id"
        const val MODE_KEY = "silo_mode"
        const val MODE_BACKGROUND_WAKE = "background_wake"
        private val DELIVERY_ID_KEYS =
            listOf(SILO_DELIVERY_ID_KEY, DELIVERY_ID_KEY, "deliveryId", "deliveryID", "id")
    }
}

class SiloFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val handler = runCatching { get<PushMessageHandler>(PushMessageHandler::class.java) }.getOrNull()
            ?: return
        serviceScope.launch {
            handler.handle(
                data = message.data,
                fallbackTitle = message.notification?.title,
                fallbackBody = message.notification?.body,
            )
        }
    }

    override fun onNewToken(token: String) {
        val registrar = runCatching { get<AndroidPushRegistrar>(AndroidPushRegistrar::class.java) }.getOrNull()
            ?: return
        serviceScope.launch {
            runCatching { registrar.registerToken(token) }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
