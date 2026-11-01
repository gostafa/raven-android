package com.gostafa.raven.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_receipts (partId TEXT NOT NULL, handledDueAt INTEGER NOT NULL, PRIMARY KEY(partId), FOREIGN KEY(partId) REFERENCES content_parts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

@Database(
    entities = [FolderEntity::class, CardEntity::class, ContentPartEntity::class, MediaEntity::class, ReviewHistoryEntity::class, ReminderReceiptEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class RavenDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun folderDao(): FolderDao
    abstract fun cardDao(): CardDao
    abstract fun contentPartDao(): ContentPartDao
    abstract fun mediaDao(): MediaDao
    abstract fun reviewHistoryDao(): ReviewHistoryDao
}
