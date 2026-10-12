package org.siloserver.silo.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
            issuer = issuerFrom(data),
            fallbackTitle = fallbackTitle ?: data["title"],
            fallbackBody = fallbackBody ?: data["body"],
        )
        return true
    }

    private fun deliveryIdFrom(data: Map<String, String>): String? =
        DELIVERY_ID_KEYS.firstNotNullOfOrNull { key ->
            data[key]?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** Null unless the push names both; servers and relays that predate them name neither. */
    private fun issuerFrom(data: Map<String, String>): PushIssuer? {
        val serverId = data[SERVER_ID_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val profileId = data[PROFILE_ID_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PushIssuer(serverId, profileId)
    }

    companion object {
        // The Silo push relay sends data-only messages carrying silo_delivery_id
        // and silo_mode, plus silo_server_id and silo_profile_id from servers
        // that send them; the legacy delivery keys stay as fallbacks.
        const val SILO_DELIVERY_ID_KEY = "silo_delivery_id"
        const val DELIVERY_ID_KEY = "delivery_id"
        const val MODE_KEY = "silo_mode"
        const val MODE_BACKGROUND_WAKE = "background_wake"

        /** The server deployment and profile the push was sent for. */
        const val SERVER_ID_KEY = "silo_server_id"
        const val PROFILE_ID_KEY = "silo_profile_id"
        private val DELIVERY_ID_KEYS =
            listOf(SILO_DELIVERY_ID_KEY, DELIVERY_ID_KEY, "deliveryId", "deliveryID", "id")
    }
}

/**
 * Budget for one network call made inside an FCM callback.
 *
 * FCM dispatches every message onto a single-threaded intent executor
 * (`FcmExecutors.newIntentHandleExecutor`) and may kill the process ~20s after
 * handing one over, so a burst of deliveries queues head-to-tail on one
 * thread — each callback has to give the thread back promptly or it eats the
 * next delivery's window. Deliberately below the shared client's 10s connect
 * timeout ([org.siloserver.silo.network.createSiloClient]): a server we
 * haven't even connected to by then won't produce anything useful inside the
 * window either, and giving up early costs only generic notification text.
 */
internal const val PUSH_WORK_BUDGET_MS = 8_000L

/**
 * Runs [block] inside [PUSH_WORK_BUDGET_MS], yielding null if it times out or
 * fails. Cancellation is rethrown ahead of the broad catch — the convention the
 * shared repositories already follow — so a swallowed failure can never be
 * mistaken for a completed one.
 */
internal suspend fun <T> withinPushBudget(block: suspend () -> T): T? =
    withTimeoutOrNull(PUSH_WORK_BUDGET_MS) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

/**
 * Both callbacks block their calling thread on purpose.
 *
 * FCM's `EnhancedIntentService` completes the wakeful intent (releasing the
 * wake lock) and calls `stopSelf()` the instant these callbacks return, so any
 * work handed off to a service-scoped coroutine gets cancelled by `onDestroy`
 * mid-flight. For [onMessageReceived] that cancellation is invisible —
 * `safeApiCall` catches `CancellationException` like any other exception — so
 * the delivery-row fetch silently lost its race against `stopSelf` on every
 * slow connection and the notification degraded to generic fallback text. For
 * [onNewToken] it silently dropped token re-registration.
 *
 * Both callbacks run on the SDK's intent-handling executor (never the main
 * thread), so running inline is safe; [PUSH_WORK_BUDGET_MS] keeps each one
 * inside the window FCM allows. A token rotation spends one budget per saved
 * server it is registered with; rotations are rare enough to afford that.
 */
class SiloFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val handler = runCatching { get<PushMessageHandler>(PushMessageHandler::class.java) }.getOrNull()
            ?: return
        // Budgeted inside the presenter rather than here, so a stalled server
        // still ends in a posted notification rather than none at all. The catch
        // is a boundary net: nothing may escape into the Firebase SDK.
        runBlocking {
            runCatching {
                handler.handle(
                    data = message.data,
                    fallbackTitle = message.notification?.title,
                    fallbackBody = message.notification?.body,
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        val registrar = runCatching { get<AndroidPushRegistrar>(AndroidPushRegistrar::class.java) }.getOrNull()
            ?: return
        // Every saved server registered here gets the new token, each within
        // its own budget. The catch is a boundary net, as above.
        runBlocking {
            runCatching { registrar.registerRotatedToken(token) }
        }
    }
}
