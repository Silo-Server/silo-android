package org.siloserver.silo.common.downloads

import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DurableLoginAuthority
import io.ktor.http.HttpStatusCode
import kotlin.test.*

class DownloadByteOwnershipTest {
    @Test fun persistedWorkCannotAdoptAnotherLoginProfileOriginOrDevice() {
        val owner = DurableLoginAuthority("login", AuthScopeSnapshot("server", "profile", "https://example.invalid", null))
        fun matches(login: String? = "login", origin: String? = owner.scope.serverUrl, device: String? = "device",
            server: String = "server", profile: String = "profile") =
            downloadWorkMatchesOwner(login, origin, device, server, profile, owner, "device")
        assertTrue(matches())
        assertFalse(matches(login = null)); assertFalse(matches(login = "new-login"))
        assertFalse(matches(origin = "https://other.invalid")); assertFalse(matches(device = "other"))
        assertFalse(matches(server = "other")); assertFalse(matches(profile = "other"))
    }
    @Test fun byteStreamDoesNotTreatEmptySuccessOrRedirectAsMedia() {
        assertNull(downloadHttpStatusFailure(HttpStatusCode.OK))
        assertNull(downloadHttpStatusFailure(HttpStatusCode.PartialContent))
        assertNotNull(downloadHttpStatusFailure(HttpStatusCode.NoContent))
        assertNotNull(downloadHttpStatusFailure(HttpStatusCode.TemporaryRedirect))
    }
}
