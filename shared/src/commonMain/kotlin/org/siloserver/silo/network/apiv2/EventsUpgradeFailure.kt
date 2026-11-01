package org.siloserver.silo.network.apiv2

import kotlinx.io.IOException

/** An upgrade may already have consumed its proof. Only a fresh mint can retry it. */
internal class EventsUpgradeFailure(val code: Int, val retryAfterMs: Long? = null) :
    IOException("Realtime upgrade refused")
