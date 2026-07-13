package org.siloserver.silo.common.player

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaAuthSessionTest {
    @Test
    fun transportNeutralSessionRefreshesAndReturnsFreshHeaders() {
        val tokens = TokenManagerImpl()
        runBlocking {
            tokens.setServerUrl("https://silo.example")
            tokens.saveTokens("expired-access", "refresh-token", 3600)
            tokens.setProfileId("profile-1")
        }
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}"""
                            .toResponseBody(null),
                    )
                    .build()
            }
            .build()
        val session = MediaAuthSession(tokens, refreshClient)

        runBlocking {
            val failed = session.snapshot()
            assertEquals("Bearer expired-access", failed.asRequestHeaders()["Authorization"])
            assertTrue(session.refreshIfStale(failed))
            val fresh = session.snapshot().asRequestHeaders()
            assertEquals("Bearer fresh-access", fresh["Authorization"])
            assertEquals("profile-1", fresh["X-Profile-Id"])
        }
    }
}
