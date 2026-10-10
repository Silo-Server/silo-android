package org.siloserver.silo.tv.cast

import org.siloserver.silo.network.AndroidServerRegistry

/**
 * Decides when a phone's handoff offer can skip the server-mediated profile
 * exchange because the TV is already signed in as the offered profile on the
 * same server. Mirrors silo-apple's `RemotePlaybackOwnIdentityPolicy`.
 *
 * The fast path never grants authorization: [authorizedAtHello] must already
 * hold, so a cross-server controller always takes the full handoff.
 */
internal object RemotePlaybackOwnIdentityPolicy {
    fun accepts(
        /** The controller's hello matched this TV's own server. */
        authorizedAtHello: Boolean,
        /** A phone's temporary identity is installed, or still being ended. */
        holdsTemporaryIdentity: Boolean,
        signedIn: Boolean,
        ownServerId: String?,
        ownProfileId: String?,
        offerServerId: String,
        offerProfileId: String,
    ): Boolean =
        authorizedAtHello &&
            !holdsTemporaryIdentity &&
            signedIn &&
            AndroidServerRegistry.serverIdsMatch(ownServerId, offerServerId) &&
            !ownProfileId.isNullOrBlank() &&
            ownProfileId == offerProfileId
}
