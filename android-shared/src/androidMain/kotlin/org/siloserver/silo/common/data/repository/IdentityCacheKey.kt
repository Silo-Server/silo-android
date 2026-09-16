package org.siloserver.silo.common.data.repository

import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.network.AuthScopeSnapshot

/**
 * Cache key bound to every field that distinguishes one acting identity from
 * another. Both the Home and library-section caches use this so a new field on
 * [AuthScopeSnapshot] is added in one place.
 */
internal fun AuthScopeSnapshot.identityCacheKey(prefix: String): String {
    val identity = Json.encodeToString(
        ListSerializer(String.serializer().nullable),
        listOf(serverId, serverUrl, profileId, profileToken, credentialGenerationId,
            identityGeneration.toString(), isIdentityGenerationStamped.toString(), credentialEpoch.toString()),
    )
    val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    return "$prefix:$hash"
}
