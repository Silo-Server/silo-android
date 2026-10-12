package org.siloserver.silo.android.auth

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.android.push.AndroidPushRegistrar
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AuthRepository

/**
 * The one sign-out of the active server's account, shared by Settings, the
 * profile menu and device pairing's "Switch account". Pending device settings
 * are pushed first, while the session they belong to still exists; then the
 * per-profile caches are dropped so the next account's shell doesn't render
 * (or write back) the previous profile's values.
 *
 * Signing out of Silo leaves the provider's session in the browser, so the
 * server's next provider sign-in asks the provider to offer another account
 * ([NativeSignInCoordinator.requestAccountChoice]). A session that expired
 * never comes through here and never asks.
 */
class SignOutTeardown(
    private val authRepository: AuthRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val titleArtStore: TitleArtStore,
    private val episodeSpoilerStore: EpisodeSpoilerStore,
    private val requestsFeatureStore: RequestsFeatureStore,
    private val metadataAiFeatureStore: MetadataAiFeatureStore,
    private val serverRegistry: ServerRegistry,
    private val nativeSignIn: NativeSignInCoordinator,
    /** Removes this device's push registration on a server ([AndroidPushRegistrar.unregisterDevice]). */
    private val unregisterPush: suspend (serverEntryId: String) -> Unit = {},
) {
    /**
     * Push settings still waiting to sync. Call before switching servers so
     * they reach the server they were made on.
     */
    suspend fun flushPendingSettings() {
        playerSettingsStore.flushPendingDeviceSettings()
    }

    /**
     * Sign out of the active server and drop per-profile state.
     * [flushFirst] is false only when the caller already flushed, before a
     * server switch. [startSignIn] ("Not you? Switch account" where the
     * provider is asked to offer another account) has the login screen that
     * follows start the provider sign-in by itself.
     *
     * False when another server became active while the push registration was
     * being removed: nobody is signed out then, as whoever is active now is not
     * who asked to leave.
     */
    suspend fun signOut(flushFirst: Boolean = true, startSignIn: Boolean = false): Boolean {
        if (flushFirst) flushPendingSettings()
        val signedOutOf = serverRegistry.activeServerId.value
        // While the credentials removing it needs still exist. A registration
        // left behind keeps the server pushing this device for an account it
        // no longer holds, but failing to remove it never stops the sign-out.
        // Cancellation still does, before anyone is signed out.
        if (signedOutOf != null) {
            try {
                unregisterPush(signedOutOf)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        // The removal can wait on the network, and logout() signs out whichever
        // server is active when it runs.
        if (serverRegistry.activeServerId.value != signedOutOf) return false
        authRepository.logout()
        nativeSignIn.discardPending()
        if (signedOutOf != null) nativeSignIn.requestAccountChoice(signedOutOf, autoStart = startSignIn)
        libraryPlaybackPrefsStore.clear()
        overlayPrefsStore.clear()
        activeProfileStore.reset()
        cardPresentationStore.clear()
        seekIntervalStore.clear()
        titleArtStore.clear()
        episodeSpoilerStore.clear()
        requestsFeatureStore.reset()
        metadataAiFeatureStore.reset()
        return true
    }
}
