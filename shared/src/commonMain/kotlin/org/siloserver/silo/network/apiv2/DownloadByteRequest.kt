package org.siloserver.silo.network.apiv2

import io.ktor.client.request.HttpRequestBuilder
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth

/** Managed byte streams require the saved request identity even after token refresh. */
fun HttpRequestBuilder.managedDownloadAuth(scope: AuthScopeSnapshot) {
    authScope(scope)
    requireSiloAuth()
}
