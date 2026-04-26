package com.clipsync.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.clipsync.app.data.entities.ClipboardEntity
import com.clipsync.app.data.entities.DeviceEntity

/**
 * Room database for ClipSync.
 * Contains clipboard history and device tables.
 */
@Database(
    entities = [ClipboardEntity::class, DeviceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao
    abstract fun deviceDao(): DeviceDao

    companion object {
        private const val DATABASE_NAME = "clipsync_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
