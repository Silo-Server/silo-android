package org.siloserver.silo.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey

/**
 * An auth scope captured at a point in time, used to **pin** a single API
 * request to a specific server + profile regardless of the globally-active
 * scope (Track B outbox drain). Without this, a background replay started for
 * server A could be sent with server B's headers if the user switches mid-send.
 *
 * The credential identity is frozen by [credentialGenerationId] in addition to
 * the profile identity ([profileId] and [profileToken]). A null generation is
 * the saved per-server account; a non-null generation identifies one temporary
 * remote-playback overlay. This prevents an in-flight request from falling
 * through to the saved account when a temporary overlay ends, or vice versa.
 *
 * [profileToken] freezes the per-server stored profile token, of which there is
 * exactly one — a p1→p2→p1 switch would otherwise lose p1's token. Access and
 * refresh tokens are read live from the captured credential slot at send time
 * because they rotate on refresh; freezing their values would break the second
 * op in a drain after the first triggers a token rotation.
 */
data class AuthScopeSnapshot(
    val serverId: String,
    val profileId: String?,
    val serverUrl: String,
    val profileToken: String?,
    val credentialGenerationId: String? = null,
)

/** Attribute carrying the [AuthScopeSnapshot] that [SiloAuthPlugin] honors. */
val AuthScopeAttributeKey: AttributeKey<AuthScopeSnapshot> = AttributeKey("SiloAuthScope")

/**
 * Attribute marking a request as intentionally unauthenticated.
 *
 * Used for candidate-server probes before the active server scope is changed:
 * those calls must not leak the current server's bearer/profile credentials or
 * trigger refresh/invalidation against the active account on a candidate 401.
 */
val SkipSiloAuthAttributeKey: AttributeKey<Boolean> = AttributeKey("SiloSkipAuth")

/** Pin this request to [snapshot]'s scope; [SiloAuthPlugin] uses it verbatim. */
fun HttpRequestBuilder.authScope(snapshot: AuthScopeSnapshot) {
    attributes.put(AuthScopeAttributeKey, snapshot)
}

/** Opt this request out of Silo auth/profile headers and auth-refresh handling. */
fun HttpRequestBuilder.skipSiloAuth() {
    attributes.put(SkipSiloAuthAttributeKey, true)
}
