package org.siloserver.silo.common.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.siloserver.silo.common.data.db.dao.MembershipProjectionDao
import org.siloserver.silo.common.data.db.entity.MembershipProjectionEntity
import org.siloserver.silo.common.data.db.entity.LegacyMembershipQuarantineEntity
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
        MembershipProjectionEntity::class,
        LegacyMembershipQuarantineEntity::class,
    ],
    version = 17,
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
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
    ],
)
abstract class SiloDatabase : RoomDatabase() {
    abstract fun membershipProjectionDao(): MembershipProjectionDao
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

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS membership_projection (authority TEXT NOT NULL, itemId TEXT NOT NULL, kind TEXT NOT NULL, commandId INTEGER NOT NULL, present INTEGER NOT NULL, disposition TEXT, PRIMARY KEY(authority, itemId, kind))")
                db.execSQL("CREATE TABLE IF NOT EXISTS legacy_membership_quarantine (commandId INTEGER NOT NULL PRIMARY KEY, originalState TEXT NOT NULL)")
                db.execSQL("INSERT INTO legacy_membership_quarantine SELECT id, state FROM dirty_operations WHERE opKind = 'SET_FAVORITE'")
                db.execSQL("UPDATE dirty_operations SET state = 'legacy_membership_quarantined' WHERE opKind = 'SET_FAVORITE'")
                installProducerGuard(db)
            }
        }

        /**
         * v16 adds `accountId` (the saved account, see
         * [org.siloserver.silo.model.server.AccountEntry.localAccountId]) to every
         * table keyed by server + profile, and to their primary keys, because
         * two accounts on one server can both have a `default` profile. Existing
         * rows get `''`, "written before accounts existed"; the account that
         * adopts the server's old data claims them at startup
         * (`OrphanedServerDataPurger`).
         *
         * SQLite cannot change a primary key in place, so those tables are
         * rebuilt: create the v16 shape, copy every v15 column, swap. The
         * outbox keeps its autoincrement id key and only gains the column.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ACCOUNT_REBUILDS.forEach { it.apply(db) }
                db.execSQL("ALTER TABLE `dirty_operations` ADD COLUMN `accountId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v17 makes local resume positions an offline queue (silo-android#846).
         * Only downloaded playback writes one now, and it waits in the outbox
         * until the server accepts it. Once-off cleanup, schema unchanged:
         * - legacy `SET_POSITION` rows were held unsent forever; drop them;
         * - positions for anything that isn't a download on this device were
         *   streaming samples the server already has; zero them, keeping track
         *   choices and reading state on the same rows;
         * - video download positions never reached the server; queue each one
         *   with the time it was played, so the server's newest-write-wins
         *   decides whether it still matters.
         * Audiobook download positions stay local; that player keeps its own.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM dirty_operations WHERE opKind = 'SET_POSITION'")
                db.execSQL(
                    "UPDATE user_item_state SET positionSeconds = 0 WHERE positionSeconds <> 0 AND NOT EXISTS (" +
                        "SELECT 1 FROM downloads d WHERE d.serverId = user_item_state.serverId " +
                        "AND d.accountId = user_item_state.accountId AND d.profileId = user_item_state.profileId " +
                        "AND d.contentId = user_item_state.contentId)",
                )
                db.execSQL(
                    "INSERT INTO dirty_operations (opKind, serverId, accountId, profileId, targetContentId, targetFileId, " +
                        "coalesceKey, idempotencyKey, opVersion, payloadJson, state, createdAtMs, attemptCount, nextAttemptAtMs) " +
                        "SELECT 'SET_POSITION', s.serverId, s.accountId, s.profileId, s.contentId, s.fileId, " +
                        "s.serverId || '|' || s.accountId || '|' || s.profileId || '|' || s.contentId || '|SET_POSITION|upgrade-' || s.fileId, " +
                        "lower(hex(randomblob(16))), 2, " +
                        "'{\"position\":' || s.positionSeconds || ',\"duration\":' || COALESCE(s.durationSeconds, 'null') || '}', " +
                        "'pending', s.positionUpdatedAtMs, 0, 0 " +
                        // '' is a temporary sign-in or pre-account data: no durable account to send as.
                        // Without a play time the event can't be ordered against the server's.
                        "FROM user_item_state s WHERE s.positionSeconds > 0 AND s.accountId <> '' " +
                        "AND s.positionUpdatedAtMs IS NOT NULL AND EXISTS (" +
                        "SELECT 1 FROM downloads d WHERE d.serverId = s.serverId AND d.accountId = s.accountId " +
                        "AND d.profileId = s.profileId AND d.contentId = s.contentId AND d.mediaFileId = s.fileId " +
                        "AND d.mediaType NOT IN ('audiobook', 'ebook'))",
                )
            }
        }

        private class TableRebuild(val table: String, val createNew: String, vararg val indices: String) {
            fun apply(db: SupportSQLiteDatabase) {
                db.execSQL(createNew)
                val columns = mutableListOf<String>()
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) columns += "`${cursor.getString(name)}`"
                }
                val list = columns.joinToString(", ")
                db.execSQL("INSERT INTO `${table}_new` ($list) SELECT $list FROM `$table`")
                db.execSQL("DROP TABLE `$table`")
                db.execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
                indices.forEach(db::execSQL)
            }
        }

        private val ACCOUNT_REBUILDS = listOf(
            TableRebuild(
                "downloads",
                "CREATE TABLE IF NOT EXISTS `downloads_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `mediaFileId` INTEGER NOT NULL, `recordId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `posterUrl` TEXT, `posterThumbhash` TEXT, `year` INTEGER, `seriesTitle` TEXT, `seriesContentId` TEXT, `seasonNumber` INTEGER, `episodeNumber` INTEGER, `fileName` TEXT, `container` TEXT, `localUri` TEXT, `mediaType` TEXT NOT NULL, `overview` TEXT, `author` TEXT, `narrator` TEXT, `durationSeconds` REAL, `chaptersJson` TEXT, `status` TEXT NOT NULL, `kind` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `bytesSent` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `completedAt` TEXT, `updatedAtMs` INTEGER NOT NULL, `resumeValidator` TEXT, `quality` TEXT, `effectiveQuality` TEXT, `revision` INTEGER, `offlineTracksJson` TEXT, `offlinePosterPath` TEXT, `offlineSeriesPosterPath` TEXT, `seriesPosterThumbhash` TEXT, `posterIsEpisodeStill` INTEGER, `episodeUserDataJson` TEXT, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `mediaFileId`))",
                "CREATE INDEX IF NOT EXISTS `index_downloads_serverId_accountId_profileId_status` ON `downloads` (`serverId`, `accountId`, `profileId`, `status`)",
                "CREATE INDEX IF NOT EXISTS `index_downloads_serverId_accountId_profileId_contentId` ON `downloads` (`serverId`, `accountId`, `profileId`, `contentId`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_recordId` ON `downloads` (`recordId`)",
            ),
            TableRebuild(
                "download_subscriptions",
                "CREATE TABLE IF NOT EXISTS `download_subscriptions_new` (`id` TEXT NOT NULL, `serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, `displayTitle` TEXT NOT NULL, `mediaKind` TEXT NOT NULL, `quality` TEXT NOT NULL, `wifiOnly` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `includeExisting` INTEGER NOT NULL, `keepUnwatchedLimit` INTEGER NOT NULL, `deleteWatchedAfterDays` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastEvaluatedAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `id`))",
                "CREATE INDEX IF NOT EXISTS `index_download_subscriptions_serverId_accountId_profileId_enabled` ON `download_subscriptions` (`serverId`, `accountId`, `profileId`, `enabled`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_subscriptions_serverId_accountId_profileId_targetType_targetId` ON `download_subscriptions` (`serverId`, `accountId`, `profileId`, `targetType`, `targetId`)",
            ),
            TableRebuild(
                "download_deletions",
                "CREATE TABLE IF NOT EXISTS `download_deletions_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `mediaFileId` INTEGER, `enqueuedAtMs` INTEGER NOT NULL, `loginId` TEXT, `origin` TEXT, `deviceId` TEXT, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `recordId`))",
            ),
            TableRebuild(
                "user_item_state",
                "CREATE TABLE IF NOT EXISTS `user_item_state_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `fileId` INTEGER NOT NULL, `positionSeconds` REAL NOT NULL, `durationSeconds` REAL, `audioFingerprint` TEXT, `subtitleFingerprint` TEXT, `cfi` TEXT, `readProgress` REAL, `clientUpdatedAtMs` INTEGER NOT NULL, `serverUpdatedAtMs` INTEGER, `positionUpdatedAtMs` INTEGER, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `contentId`, `fileId`))",
                "CREATE INDEX IF NOT EXISTS `index_user_item_state_serverId_accountId_profileId_contentId` ON `user_item_state` (`serverId`, `accountId`, `profileId`, `contentId`)",
                "CREATE INDEX IF NOT EXISTS `index_user_item_state_serverId_accountId_profileId_clientUpdatedAtMs` ON `user_item_state` (`serverId`, `accountId`, `profileId`, `clientUpdatedAtMs`)",
            ),
            TableRebuild(
                "content_item_state",
                "CREATE TABLE IF NOT EXISTS `content_item_state_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `watched` INTEGER, `ratingValue` INTEGER, `favorite` INTEGER, `clientUpdatedAtMs` INTEGER NOT NULL, `serverUpdatedAtMs` INTEGER, `watchedUpdatedAtMs` INTEGER, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `contentId`))",
            ),
            TableRebuild(
                "home_cache",
                "CREATE TABLE IF NOT EXISTS `home_cache_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `sectionsJson` TEXT NOT NULL, `cachedAtMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `accountId`, `profileId`))",
            ),
            TableRebuild(
                "catalog_cache",
                "CREATE TABLE IF NOT EXISTS `catalog_cache_new` (`serverId` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', `profileId` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, `json` TEXT NOT NULL, `cachedAtMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `accountId`, `profileId`, `cacheKey`))",
            ),
        )

        val CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = installProducerGuard(db)
        }

        private fun installProducerGuard(db: SupportSQLiteDatabase) {
            // Abort (not IGNORE/NONE): roll back the old producer's optimistic projection too.
            db.execSQL("CREATE TRIGGER IF NOT EXISTS reject_legacy_membership BEFORE INSERT ON dirty_operations WHEN NEW.opKind = 'SET_FAVORITE' BEGIN SELECT RAISE(ABORT, 'Legacy membership producer requires coordinated cutover'); END")
        }


        /**
         * Builds the on-disk database. Room is an `android-shared` implementation
         * detail, so the app DI modules construct the DB through this factory
         * rather than referencing `androidx.room` (which is not on their compile
         * classpath).
         */
        fun build(context: Context): SiloDatabase =
            Room.databaseBuilder(context.applicationContext, SiloDatabase::class.java, NAME)
                .addMigrations(MIGRATION_9_10, MIGRATION_15_16, MIGRATION_16_17).addCallback(CALLBACK).build()
    }
}
