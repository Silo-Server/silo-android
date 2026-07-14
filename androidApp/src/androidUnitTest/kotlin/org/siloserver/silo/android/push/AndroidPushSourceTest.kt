package org.siloserver.silo.android.push

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidPushSourceTest {
    @Test
    fun phonePushHasTokenProviderMessagingServiceAndGenericPresenter() {
        val provider = File("src/androidMain/kotlin/org/siloserver/silo/android/push/AndroidPushTokenProvider.kt")
            .takeIf { it.exists() }
            ?.readText()
            .orEmpty()
        val service = File("src/androidMain/kotlin/org/siloserver/silo/android/push/SiloFirebaseMessagingService.kt")
            .takeIf { it.exists() }
            ?.readText()
            .orEmpty()
        val presenter = File("src/androidMain/kotlin/org/siloserver/silo/android/push/PushNotificationPresenter.kt")
            .takeIf { it.exists() }
            ?.readText()
            .orEmpty()
        val app = File("src/androidMain/kotlin/org/siloserver/silo/android/SiloApplication.kt").readText()
        val activity = File("src/androidMain/kotlin/org/siloserver/silo/android/MainActivity.kt").readText()
        val routes = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/Routes.kt").readText()
        val navigation = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt").readText()
        val manifest = File("src/androidMain/AndroidManifest.xml").readText()
        val gradle = File("build.gradle.kts").readText()

        assertTrue(gradle.contains("firebase.messaging"))
        assertTrue(gradle.contains("google-services.json"))
        assertTrue(gradle.contains("com.google.gms.google-services"))
        assertTrue(provider.contains("FirebaseAndroidPushTokenProvider"))
        assertTrue(provider.contains("FirebaseApp.initializeApp"))
        assertTrue(provider.contains("FirebaseMessaging.getInstance().token"))
        assertTrue(service.contains(": FirebaseMessagingService()"))
        assertTrue(service.contains("onMessageReceived"))
        assertTrue(service.contains("onNewToken"))
        assertTrue(service.contains("delivery_id"))
        // The relay's fixed content-private data payload carries exactly these keys.
        assertTrue(service.contains("silo_delivery_id"))
        assertTrue(service.contains("silo_mode"))
        assertTrue(service.contains("background_wake"))
        assertTrue(manifest.contains("SiloFirebaseMessagingService"))
        assertTrue(app.contains("AndroidPushRegistrationStarter"))
        assertTrue(presenter.contains("notificationContentFor"))
        assertTrue(presenter.contains("EXTRA_NAV_ROUTE"))
        assertTrue(activity.contains("notificationRouteOrNull"))
        assertTrue(routes.contains("data object Inbox"))
        assertTrue(navigation.contains("InboxScreen("))
        assertTrue(manifest.contains("POST_NOTIFICATIONS"))
    }
}
