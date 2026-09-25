package org.siloserver.silo.model.watchtogether

/** Reads one file under `watchtogether/v2/` from the test resources. See its SOURCE file. */
expect fun readWatchPartyFixture(name: String): String

/** Class-loader anchor for the Android actual. */
internal object WatchPartyFixtureAnchor
