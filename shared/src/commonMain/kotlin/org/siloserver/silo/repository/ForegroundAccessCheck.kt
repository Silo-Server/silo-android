package org.siloserver.silo.repository

import org.siloserver.silo.network.AccessChangeSignals

/**
 * Catches an access change made while the app was in the background.
 *
 * The events sockets are the only source of [AccessChangeSignals.reportAccessChanged],
 * and they close when the app stops. The server compares each connection with
 * the policy its own ticket was minted under, so a connection opened after the
 * change never reports it. When the app returns to the foreground, this check
 * fetches the library list and reports an access change when the set of
 * libraries differs from the cached list the screens were built from.
 *
 * It sees only changes to which libraries are visible. Other policy changes
 * (maturity limits, playback quality) still surface when the affected screen
 * next loads.
 */
class ForegroundAccessCheck(
    private val personalDataRepository: PersonalDataRepository,
    private val accessChanges: AccessChangeSignals,
) {
    suspend fun afterReturnToForeground() {
        if (personalDataRepository.libraryListChangedSinceCached()) accessChanges.reportAccessChanged()
    }
}
