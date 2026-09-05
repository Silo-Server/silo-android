package org.siloserver.silo.common.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.siloserver.silo.common.data.db.dao.MembershipProjectionDao
import org.siloserver.silo.common.data.db.entity.*

/**
 * Unbound candidate schema. Production still opens SiloDatabase version 9.
 * No app builder, DI binding, scheduler or producer references this class.
 * Enable only with the coordinated membership producer AND UI cutover.
 */
@Database(
    entities = [
        UserItemStateEntity::class,
        ContentItemStateEntity::class,
        DirtyOperationEntity::class,
        DownloadEntity::class,
        LegacyImportEntity::class,
        HomeCacheEntity::class,
        CatalogCacheEntity::class,
        DownloadDeletionEntity::class,
        DownloadSubscriptionEntity::class,
        MembershipProjectionEntity::class,
        LegacyMembershipQuarantineEntity::class,
    ],
    version = 10,
    exportSchema = true,

)
abstract class DormantMembershipDatabase : SiloDatabase() {
    abstract fun membershipProjectionDao(): MembershipProjectionDao
    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS membership_projection (authority TEXT NOT NULL, itemId TEXT NOT NULL, kind TEXT NOT NULL, commandId INTEGER NOT NULL, present INTEGER NOT NULL, disposition TEXT, PRIMARY KEY(authority, itemId, kind))")
                db.execSQL("CREATE TABLE IF NOT EXISTS legacy_membership_quarantine (commandId INTEGER NOT NULL PRIMARY KEY, originalState TEXT NOT NULL)")
                db.execSQL("INSERT INTO legacy_membership_quarantine SELECT id, state FROM dirty_operations WHERE opKind = 'SET_FAVORITE'")
                db.execSQL("UPDATE dirty_operations SET state = 'legacy_membership_quarantined' WHERE opKind = 'SET_FAVORITE'")
                installProducerGuard(db)
            }
        }

        val CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = installProducerGuard(db)
        }

        private fun installProducerGuard(db: SupportSQLiteDatabase) {
            // Abort (not IGNORE/NONE): roll back the old producer's optimistic projection too.
            db.execSQL("CREATE TRIGGER IF NOT EXISTS reject_legacy_membership BEFORE INSERT ON dirty_operations WHEN NEW.opKind = 'SET_FAVORITE' BEGIN SELECT RAISE(ABORT, 'Legacy membership producer requires coordinated cutover'); END")
        }
    }
}
