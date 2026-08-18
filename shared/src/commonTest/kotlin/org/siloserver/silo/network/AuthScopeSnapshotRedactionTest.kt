package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthScopeSnapshotRedactionTest {

    /**
     * Snapshots reach logs and diagnostics bundles through interpolation. The
     * durable credentialOwnerId is a stable cross-launch account identifier, so
     * it is redacted alongside the other identity fields rather than left as
     * the one value that survives into a shipped log.
     */
    @Test
    fun toStringRedactsTheDurableCredentialOwnerAlongWithEveryOtherIdentityField() {
        val secrets = listOf(
            "server-id-secret",
            "profile-id-secret",
            "https://private.example",
            "profile-token-secret",
            "overlay-generation-secret",
            "11111111-2222-3333-4444-555555555555",
        )
        val snapshot = AuthScopeSnapshot(
            serverId = secrets[0],
            profileId = secrets[1],
            serverUrl = secrets[2],
            profileToken = secrets[3],
            credentialGenerationId = secrets[4],
            identityGeneration = 7L,
            isIdentityGenerationStamped = true,
            credentialOwnerId = secrets[5],
            credentialEpoch = 9L,
        )

        val rendered = snapshot.toString()

        assertTrue(rendered.contains("credentialOwnerId=<redacted>"), rendered)
        secrets.forEach { secret ->
            assertFalse(rendered.contains(secret), "leaked $secret in $rendered")
        }
    }
}
