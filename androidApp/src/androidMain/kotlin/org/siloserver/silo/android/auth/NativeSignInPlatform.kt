package org.siloserver.silo.android.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import org.siloserver.silo.android.MainActivity
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.repository.AuthRepository

/**
 * Signs in through [AuthRepository] and links through [ExternalSignInApi], at
 * the saved server's base URL, and only while that server is still at the
 * origin the flow was started on ([PendingNativeSignIn.startOrigin]). The
 * saved server keeps its address and id: a sign-in never moves, re-keys or
 * duplicates it.
 */
class RepositoryNativeSignInCompleter(
    private val authRepository: AuthRepository,
    private val api: ExternalSignInApi,
    private val tokenManager: TokenManager,
) : NativeSignInCompleter {
    override suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User> {
        return authRepository.completeNativeOAuthLogin(pending.serverEntryId) { serverUrl ->
            if (NativeSignInProtocol.origin(serverUrl) != pending.startOrigin) {
                changed()
            } else {
                api.completeOAuthLogin(serverUrl, code, pending.codeVerifier)
            }
        }
    }

    /**
     * Only the account session that asked for the link ticket may confirm the
     * link: the server checks the account, and this checks the session did not
     * change (sign-out, another account, another server or address) while the
     * browser was open.
     */
    override suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit> {
        val scope = tokenManager.snapshotCurrentScope()
        if (scope == null || !scope.startedFlow(pending) || NativeSignInProtocol.origin(scope.serverUrl) != pending.startOrigin) {
            return changed()
        }
        return api.completeLink(scope, code, pending.codeVerifier)
    }

    private fun AuthScopeSnapshot.startedFlow(pending: PendingNativeSignIn): Boolean =
        serverId == pending.serverEntryId &&
            identityGeneration == pending.identityGeneration && credentialEpoch == pending.credentialEpoch

    private fun changed() = ApiResult.Error(0, "identity_changed", "The account or server changed.")
}

/**
 * The pending flow in the app's encrypted preferences, so a process the
 * system killed while the browser was open can still finish it.
 */
class SharedPrefsPendingNativeSignInStore(private val prefs: SharedPreferences) : PendingNativeSignInStore {
    override fun load(): PendingNativeSignIn? = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(PendingNativeSignIn.serializer(), it) }.getOrNull()
    }

    override fun save(pending: PendingNativeSignIn) {
        prefs.edit(commit = true) { putString(KEY, json.encodeToString(PendingNativeSignIn.serializer(), pending)) }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY) }
    }

    private companion object {
        const val KEY = "native_sign_in_pending"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** [AccountChoiceStore] in the app's encrypted preferences. */
class SharedPrefsAccountChoiceStore(private val prefs: SharedPreferences) : AccountChoiceStore {
    override fun request(serverEntryId: String) = update { it + serverEntryId }

    override fun isRequested(serverEntryId: String): Boolean = serverEntryId in read()

    override fun clear(serverEntryId: String) = update { it - serverEntryId }

    private fun read(): Set<String> = prefs.getStringSet(KEY, null).orEmpty()

    @Synchronized
    private fun update(change: (Set<String>) -> Set<String>) {
        val next = change(read())
        prefs.edit(commit = true) { if (next.isEmpty()) remove(KEY) else putStringSet(KEY, next.toSet()) }
    }

    private companion object {
        const val KEY = "native_sign_in_account_choice"
    }
}

/**
 * Opens a native flow's start URL in a Custom Tab (the system browser when
 * none supports Custom Tabs). Never a WebView: the provider's page must run
 * in the browser that holds its cookies. Returns false when no browser can
 * open it.
 */
fun Context.openNativeSignIn(url: String): Boolean = try {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .launchUrl(this, url.toUri())
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Receives `org.siloserver.silo:/auth/callback`. Hands the redirect to the
 * coordinator, which acts on it only when it answers the flow this app
 * started, then brings the app's task back to [MainActivity], closing the
 * Custom Tab above it. Shows nothing itself.
 */
class NativeSignInCallbackActivity : Activity() {
    private val coordinator: NativeSignInCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restored instance already handed its redirect over.
        if (savedInstanceState == null) coordinator.handleCallback(intent?.dataString)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
