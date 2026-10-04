package com.example.simplemediadownloader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DownloadTaskEntity::class, BatchDownloadEntity::class, BatchItemEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun batchDao(): BatchDao

    companion object {
        private const val DATABASE_NAME = "downloads.db"

        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*DownloadDatabaseMigrations.ALL)
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

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN canonical_url TEXT NOT NULL DEFAULT ''")
            database.execSQL("UPDATE download_tasks SET canonical_url = source_url WHERE canonical_url = ''")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_canonical_url ON download_tasks (canonical_url)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN author TEXT")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_author ON download_tasks (author)")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN source_extension TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN target_audio_bitrate_kbps INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN duration_seconds INTEGER")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN source_size_bytes INTEGER")
            database.execSQL("UPDATE download_tasks SET source_extension = file_extension")
            // Before schema 6, AUDIO_MP3 meant an MP3 source, never a conversion request.
            // Preserve queued native-source tasks rather than changing their output intent.
            database.execSQL("UPDATE download_tasks SET download_mode = 'AUDIO_ORIGINAL' WHERE download_mode = 'AUDIO_MP3'")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN batch_id TEXT")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN batch_index INTEGER")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN source_item_id TEXT")
            database.execSQL("ALTER TABLE download_tasks ADD COLUMN filename_prefix TEXT")
            database.execSQL("CREATE INDEX index_download_tasks_batch_id ON download_tasks (batch_id)")
            database.execSQL("CREATE INDEX index_download_tasks_batch_id_batch_index ON download_tasks (batch_id, batch_index)")
            database.execSQL("""CREATE TABLE IF NOT EXISTS download_batches (
                batch_id TEXT NOT NULL PRIMARY KEY, source_url TEXT NOT NULL, platform TEXT NOT NULL,
                collection_type TEXT NOT NULL, title TEXT, requested_count INTEGER, discovered_count INTEGER NOT NULL,
                selected_count INTEGER NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL, continuation TEXT,
                has_more INTEGER NOT NULL, download_mode TEXT NOT NULL, maximum_height INTEGER NOT NULL,
                mp3_bitrate_kbps INTEGER NOT NULL, skip_existing INTEGER NOT NULL, prefix_order INTEGER NOT NULL, error TEXT)""")
            database.execSQL("""CREATE TABLE IF NOT EXISTS batch_items (
                batch_id TEXT NOT NULL, item_id TEXT NOT NULL, url TEXT NOT NULL, title TEXT, author TEXT,
                thumbnail_url TEXT, duration_seconds INTEGER, position INTEGER NOT NULL, selected INTEGER NOT NULL,
                child_task_id TEXT, skip_reason TEXT, PRIMARY KEY(batch_id, item_id),
                FOREIGN KEY(batch_id) REFERENCES download_batches(batch_id) ON DELETE CASCADE)""")
            database.execSQL("CREATE INDEX index_batch_items_batch_id_position ON batch_items(batch_id, position)")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_batches ADD COLUMN author TEXT")
            database.execSQL("ALTER TABLE download_batches ADD COLUMN thumbnail_url TEXT")
            database.execSQL("ALTER TABLE download_batches ADD COLUMN total_item_count INTEGER")
            database.execSQL("ALTER TABLE batch_items ADD COLUMN unavailable_reason TEXT")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE download_batches ADD COLUMN discovery_page INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE download_batches ADD COLUMN discovery_notice TEXT")
            database.execSQL("ALTER TABLE batch_items ADD COLUMN published_at_seconds INTEGER")
            database.execSQL("UPDATE download_batches SET requested_count = 20 WHERE collection_type = 'SOCIAL_PROFILE' AND requested_count IS NULL")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}
