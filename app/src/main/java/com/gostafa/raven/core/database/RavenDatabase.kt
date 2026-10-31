package com.gostafa.raven.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, CardEntity::class, ContentPartEntity::class, MediaEntity::class, ReviewHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RavenDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun cardDao(): CardDao
    abstract fun contentPartDao(): ContentPartDao
    abstract fun mediaDao(): MediaDao
    abstract fun reviewHistoryDao(): ReviewHistoryDao
}
