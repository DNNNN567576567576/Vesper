package app.vesper.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Android-only: migrations 1..9 stay on SupportSQLiteDatabase, exactly as before the KMP split.
// Android opens the DB in Room compatibility mode (no driver), so these run unchanged on the phone's file.
// The desktop never migrates: it starts at the current version (see the sync plan, section A).

/** v1 -> v2: adds the Brain tables. Notes and tasks are not touched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lobes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `slot` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `ideas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lobeId` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `noteId` INTEGER, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`lobeId`) REFERENCES `lobes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ideas_lobeId` ON `ideas` (`lobeId`)")
    }
}

/** v2 -> v3: adds the Shrine table. Notes, tasks and Brain tables are not touched. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT, `imagePath` TEXT, `createdAt` INTEGER NOT NULL, `editedAt` INTEGER)")
    }
}

/** v3 -> v4: adds the Moons tables. Every existing table is left untouched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_day` ON `plans` (`day`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `day_focus` (`day` INTEGER NOT NULL, `lobeId` INTEGER NOT NULL, PRIMARY KEY(`day`, `lobeId`), FOREIGN KEY(`lobeId`) REFERENCES `lobes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_focus_lobeId` ON `day_focus` (`lobeId`)")
    }
}

/** v4 -> v5: tasks are snuffed (timestamped) instead of deleted. Existing tasks stay lit (NULL). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `snuffedAt` INTEGER")
    }
}

/** v5 -> v6: notes get a manual-sort position, starting out in the current "recently edited" order. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `notes` SET `position` = (SELECT COUNT(*) FROM `notes` n2 WHERE n2.`updatedAt` > `notes`.`updatedAt` OR (n2.`updatedAt` = `notes`.`updatedAt` AND n2.`id` > `notes`.`id`))")
    }
}

/** v6 -> v7: Grimoire posts can hold a video. Existing posts have none (NULL). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `posts` ADD COLUMN `videoPath` TEXT")
    }
}

/** v7 -> v8: notes can be pages of a main scroll. Existing notes stay top-level (NULL). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `parentId` INTEGER")
    }
}

fun createSyncTriggers(db: SupportSQLiteDatabase) = SYNCED_TABLES.forEach { t -> syncTriggerSql(t).forEach(db::execSQL) }

/**
 * v8 -> v9: sync ids and clocks. Existing rows get deterministic uids ('notes-12'), so an old backup restored later
 * migrates to the same uids and never duplicates on sync; random uids are 32 hex chars and can't collide with them.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstones` (`tbl` TEXT NOT NULL, `uid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`tbl`, `uid`))")
        val clock = mapOf("notes" to "updatedAt", "tasks" to "snuffedAt", "lobes" to "NULL", "ideas" to "updatedAt", "plans" to "createdAt")
        for (t in SYNCED_TABLES) {
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `syncAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$t` SET `uid` = '$t-' || `id`, `syncAt` = max(coalesce(${clock.getValue(t)}, $NOW_MS), 1)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_uid` ON `$t` (`uid`)")
        }
        createSyncTriggers(db) // after the backfill, so it isn't tracked as edits
    }
}

/** The single migration list used by the app and the tests. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
