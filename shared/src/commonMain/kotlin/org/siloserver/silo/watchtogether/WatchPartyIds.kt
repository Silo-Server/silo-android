package org.siloserver.silo.watchtogether

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A caller-selected room or suggestion identity. Create it once per logical
 * action and keep it for an explicit retry, so the server can replay instead
 * of creating a duplicate.
 */
@OptIn(ExperimentalUuidApi::class)
fun newWatchPartyId(): String = Uuid.random().toString()
