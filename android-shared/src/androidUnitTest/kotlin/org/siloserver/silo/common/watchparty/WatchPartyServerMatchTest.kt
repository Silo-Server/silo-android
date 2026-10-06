package org.siloserver.silo.common.watchparty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyServerMatchTest {
    @Test
    fun `an invitation matches the active server by scheme, host, port, and path`() {
        assertTrue(watchPartyServerMatches("https://media.example.com", "https://media.example.com"))
        assertTrue(watchPartyServerMatches("https://Media.Example.com/", "https://media.example.com"))
        assertTrue(watchPartyServerMatches("HTTPS://media.example.com", "https://media.example.com"))
        assertTrue(watchPartyServerMatches("https://media.example.com/silo/", "https://media.example.com/silo"))
        assertTrue(watchPartyServerMatches("https://media.example.com:443", "https://media.example.com"))
        assertTrue(watchPartyServerMatches("http://10.0.0.5:8096", "http://10.0.0.5:8096/"))
        assertTrue(watchPartyServerMatches("http://[fd00::5]:8096", "http://[FD00::5]:8096/"))
    }

    @Test
    fun `a different scheme, port, base path, or address is a different server`() {
        assertFalse(watchPartyServerMatches("http://media.example.com", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("https://media.example.com:8443", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("https://media.example.com/silo", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("https://media.example.com/Silo", "https://media.example.com/silo"))
        assertFalse(watchPartyServerMatches("http://[fd00::5]:8096", "http://[fd00::5]"))
        // A LAN address and a public address for the same server do not match.
        assertFalse(watchPartyServerMatches("http://192.168.1.10:8096", "https://media.example.com"))
    }

    @Test
    fun `no active server, credentials, or an unparseable URL never match`() {
        assertFalse(watchPartyServerMatches("https://media.example.com", null))
        assertFalse(watchPartyServerMatches("https://user@media.example.com", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("not a url", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("media.example.com", "media.example.com"))
        assertFalse(watchPartyServerMatches("https://media.example.com:port", "https://media.example.com"))
        assertFalse(watchPartyServerMatches("ftp://media.example.com", "ftp://media.example.com"))
    }
}
