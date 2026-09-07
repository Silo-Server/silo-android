package org.siloserver.silo.common.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.siloserver.silo.common.data.db.dao.CatalogCacheDao
import org.siloserver.silo.common.data.db.dao.ContentItemStateDao
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.common.data.db.dao.DownloadDao
import org.siloserver.silo.common.data.db.dao.DownloadDeletionDao
import org.siloserver.silo.common.data.db.dao.DownloadSubscriptionDao
import org.siloserver.silo.common.data.db.dao.ServerPurgeDao
import org.siloserver.silo.common.data.db.dao.HomeCacheDao
import org.siloserver.silo.common.data.db.dao.LegacyImportDao
import org.siloserver.silo.common.data.db.dao.UserItemStateDao
import org.siloserver.silo.common.data.db.entity.CatalogCacheEntity
import org.siloserver.silo.common.data.db.entity.ContentItemStateEntity
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.data.db.entity.DownloadEntity
import org.siloserver.silo.common.data.db.entity.DownloadDeletionEntity
import org.siloserver.silo.common.data.db.entity.DownloadSubscriptionEntity
import org.siloserver.silo.common.data.db.entity.HomeCacheEntity
import org.siloserver.silo.common.data.db.entity.LegacyImportEntity
import org.siloserver.silo.common.data.db.entity.UserItemStateEntity

/**
 * The offline-first store for Silo (Track B). Source of truth for the
 * home/library browse + resume + downloads + user-state paths.
 *
 * v2 adds [HomeCacheEntity] via a Room **auto migration** (additive table, so it
 * preserves the existing outbox/projection data on upgrade). From here, prefer
 * auto migrations for additive/rename changes and manual migrations for any data
 * transform, validated with `MigrationTestHelper`.
 *
 * No `@TypeConverters` are declared — every entity field is a primitive,
 * String, or JSON-encoded String (e.g. [DownloadEntity.chaptersJson]).
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
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
)
abstract class SiloDatabase : RoomDatabase() {
    abstract fun userItemStateDao(): UserItemStateDao
    abstract fun contentItemStateDao(): ContentItemStateDao
    abstract fun dirtyOperationDao(): DirtyOperationDao
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadDeletionDao(): DownloadDeletionDao
    abstract fun legacyImportDao(): LegacyImportDao
    abstract fun homeCacheDao(): HomeCacheDao
    abstract fun catalogCacheDao(): CatalogCacheDao
    abstract fun downloadSubscriptionDao(): DownloadSubscriptionDao
    abstract fun serverPurgeDao(): ServerPurgeDao

    companion object {
        const val NAME = "silo.db"

        /**
         * Builds the on-disk database. Room is an `android-shared` implementation
         * detail, so the app DI modules construct the DB through this factory
         * rather than referencing `androidx.room` (which is not on their compile
         * classpath).
         */
        fun build(context: Context): SiloDatabase =
            Room.databaseBuilder(context.applicationContext, SiloDatabase::class.java, NAME).build()
    }
}
