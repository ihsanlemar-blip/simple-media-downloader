package com.example.simplemediadownloader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DownloadTaskEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        private const val DATABASE_NAME = "downloads.db"

        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*DownloadDatabaseMigrations.ALL)
                .fallbackToDestructiveMigration()
                .build()
    }
}

object DownloadDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN downloaded_bytes INTEGER")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN total_bytes INTEGER")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN speed_bytes_per_second INTEGER")
            database.execSQL(
                """UPDATE download_tasks SET processing_stage = CASE
                    WHEN processing_stage = 'DOWNLOADING' AND download_mode = 'VIDEO'
                        THEN 'DOWNLOADING_VIDEO'
                    WHEN processing_stage = 'DOWNLOADING'
                        THEN 'DOWNLOADING_AUDIO'
                    ELSE processing_stage END""",
            )
            database.execSQL(
                """UPDATE download_tasks SET failure_category = CASE failure_category
                    WHEN 'NETWORK' THEN 'NETWORK_INTERRUPTED'
                    WHEN 'UNSUPPORTED_URL' THEN 'UNSUPPORTED_SITE'
                    WHEN 'AUTHENTICATION_REQUIRED' THEN 'PRIVATE_OR_LOGIN_REQUIRED'
                    WHEN 'CONVERTER' THEN 'CONVERTER_FAILURE'
                    WHEN 'INTERRUPTED' THEN 'ANDROID_INTERRUPTED_TASK'
                    WHEN 'UNKNOWN' THEN 'UNKNOWN_FAILURE'
                    WHEN 'STORAGE' THEN 'UNKNOWN_FAILURE'
                    ELSE failure_category END""",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN http_headers TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
