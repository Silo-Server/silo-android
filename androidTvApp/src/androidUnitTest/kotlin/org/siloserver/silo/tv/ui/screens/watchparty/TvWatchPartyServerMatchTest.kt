package org.siloserver.silo.tv.ui.screens.watchparty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWatchPartyServerMatchTest {
    @Test
    fun `the same server matches regardless of host case, default port, or trailing slash`() {
        assertTrue(tvWatchPartyServerMatches("https://Media.Example.com", "https://media.example.com/"))
        assertTrue(tvWatchPartyServerMatches("https://media.example.com:443", "https://media.example.com"))
        assertTrue(tvWatchPartyServerMatches("http://10.0.0.5:8096/silo", "http://10.0.0.5:8096/silo/"))
        assertTrue(tvWatchPartyServerMatches("HTTPS://media.example.com", "https://media.example.com"))
    }

    @Test
    fun `scheme, port, and base path must all agree`() {
        assertFalse(tvWatchPartyServerMatches("http://media.example.com", "https://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("https://media.example.com:8443", "https://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("https://media.example.com/silo", "https://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("https://media.example.com/Silo", "https://media.example.com/silo"))
    }

    @Test
    fun `a LAN address and a public name for the same server are different servers`() {
        assertFalse(tvWatchPartyServerMatches("http://192.168.1.20:8096", "https://media.example.com"))
    }

    @Test
    fun `malformed or credentialed urls never match`() {
        assertFalse(tvWatchPartyServerMatches("https://user@media.example.com", "https://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("ftp://media.example.com", "ftp://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("media.example.com", "media.example.com"))
        assertFalse(tvWatchPartyServerMatches("https://media.example.com:port", "https://media.example.com"))
        assertFalse(tvWatchPartyServerMatches("https://media.example.com", null))
    }

    @Test
    fun `bracketed ipv6 hosts compare with their ports`() {
        assertTrue(tvWatchPartyServerMatches("http://[fd00::5]:8096", "http://[FD00::5]:8096/"))
        assertFalse(tvWatchPartyServerMatches("http://[fd00::5]:8096", "http://[fd00::5]"))
    }
}
