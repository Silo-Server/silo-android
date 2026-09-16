package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity

/** A login incarnation owns this projection; server/profile alone is never sufficient. */
@Entity(tableName = "membership_projection", primaryKeys = ["authority", "itemId", "kind"])
data class MembershipProjectionEntity(
    val authority: String,
    val itemId: String,
    val kind: String,
    val commandId: Long,
    val present: Boolean,
    val disposition: String?,
)

/** All other original fields stay intact in the quarantined dirty_operations row. */
@Entity(tableName = "legacy_membership_quarantine", primaryKeys = ["commandId"])
data class LegacyMembershipQuarantineEntity(val commandId: Long, val originalState: String)
