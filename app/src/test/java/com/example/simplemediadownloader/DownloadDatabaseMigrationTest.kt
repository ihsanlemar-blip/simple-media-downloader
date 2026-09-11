package com.example.simplemediadownloader

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

private class WindowsCompatibleOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        val fullPath = configuration.name?.let { configuration.context.getDatabasePath(it).absolutePath }
        return object : SupportSQLiteOpenHelper by helper {
            override val databaseName: String?
                get() = fullPath ?: helper.databaseName
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadDatabaseMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DownloadDatabase::class.java,
        emptyList(),
        WindowsCompatibleOpenHelperFactory(),
    )

    @Test
    fun `migrate 1 to 2 preserves tasks and adds progress columns`() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                """INSERT INTO download_tasks (
                    task_id, source_url, display_title, platform, format_key, format_id,
                    download_mode, file_extension, width, height, fps, bitrate_kbps,
                    codec, format_note, size_is_approximate, source_height, requires_downscale,
                    is_quick_preset, status, processing_stage, created_at, failure_category
                ) VALUES (
                    'task-v1', 'https://example.com/v1', 'Title V1', 'generic', 'v1080', '18',
                    'VIDEO', 'mp4', 1920, 1080, 30, 2000,
                    'h264', '', 0, 1080, 0,
                    0, 'FAILED', 'DOWNLOADING', 1000, 'NETWORK'
                )""",
            )
            close()
        }

        val db2 = helper.runMigrationsAndValidate(
            testDbName,
            2,
            true,
            DownloadDatabaseMigrations.MIGRATION_1_2,
        )

        db2.query("SELECT downloaded_bytes, total_bytes, speed_bytes_per_second, processing_stage, failure_category FROM download_tasks WHERE task_id = 'task-v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Nullable progress columns default to NULL
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            // Stage was mapped from DOWNLOADING + VIDEO -> DOWNLOADING_VIDEO
            assertEquals("DOWNLOADING_VIDEO", cursor.getString(3))
            // Failure category was mapped from NETWORK -> NETWORK_INTERRUPTED
            assertEquals("NETWORK_INTERRUPTED", cursor.getString(4))
        }
    }

    @Test
    fun `migrate 2 to 3 preserves tasks and adds http_headers column`() {
        helper.createDatabase(testDbName, 2).apply {
            execSQL(
                """INSERT INTO download_tasks (
                    task_id, source_url, display_title, platform, format_key, format_id,
                    download_mode, file_extension, width, height, fps, bitrate_kbps,
                    codec, format_note, size_is_approximate, source_height, requires_downscale,
                    is_quick_preset, status, processing_stage, created_at
                ) VALUES (
                    'task-v2', 'https://example.com/v2', 'Title V2', 'generic', 'v720', '22',
                    'VIDEO', 'mp4', 1280, 720, 30, 1500,
                    'h264', '', 0, 720, 0,
                    0, 'QUEUED', 'QUEUED', 2000
                )""",
            )
            close()
        }

        val db3 = helper.runMigrationsAndValidate(
            testDbName,
            3,
            true,
            DownloadDatabaseMigrations.MIGRATION_2_3,
        )

        db3.query("SELECT task_id, display_title, http_headers FROM download_tasks WHERE task_id = 'task-v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("task-v2", cursor.getString(0))
            assertEquals("Title V2", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun `migrate 3 to 4 preserves tasks and populates canonical_url from source_url`() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                """INSERT INTO download_tasks (
                    task_id, source_url, display_title, platform, format_key, format_id,
                    download_mode, file_extension, width, height, fps, bitrate_kbps,
                    codec, format_note, size_is_approximate, source_height, requires_downscale,
                    is_quick_preset, status, processing_stage, created_at, http_headers
                ) VALUES (
                    'task-v3', 'https://example.com/video?id=123', 'Title V3', 'generic', 'v1080', '137',
                    'VIDEO', 'mp4', 1920, 1080, 60, 4000,
                    'h264', '', 0, 1080, 0,
                    0, 'COMPLETED', 'COMPLETED', 3000, '{"User-Agent":"Test"}'
                )""",
            )
            close()
        }

        val db4 = helper.runMigrationsAndValidate(
            testDbName,
            4,
            true,
            DownloadDatabaseMigrations.MIGRATION_3_4,
        )

        db4.query("SELECT task_id, source_url, canonical_url, http_headers FROM download_tasks WHERE task_id = 'task-v3'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("task-v3", cursor.getString(0))
            assertEquals("https://example.com/video?id=123", cursor.getString(1))
            // Backfilled with source_url
            assertEquals("https://example.com/video?id=123", cursor.getString(2))
            assertEquals("{\"User-Agent\":\"Test\"}", cursor.getString(3))
        }
    }

    @Test
    fun `full migration 1 to 4 preserves data integrity across all versions`() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                """INSERT INTO download_tasks (
                    task_id, source_url, display_title, platform, format_key, format_id,
                    download_mode, file_extension, width, height, fps, bitrate_kbps,
                    codec, format_note, size_is_approximate, source_height, requires_downscale,
                    is_quick_preset, status, processing_stage, created_at
                ) VALUES (
                    'task-full', 'https://example.com/full', 'Full Migration', 'generic', 'v720', '22',
                    'VIDEO', 'mp4', 1280, 720, 30, 1500,
                    'h264', '', 0, 720, 0,
                    0, 'QUEUED', 'QUEUED', 5000
                )""",
            )
            close()
        }

        val dbFinal = helper.runMigrationsAndValidate(
            testDbName,
            4,
            true,
            *DownloadDatabaseMigrations.ALL,
        )

        dbFinal.query("SELECT task_id, display_title, canonical_url FROM download_tasks WHERE task_id = 'task-full'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("task-full", cursor.getString(0))
            assertEquals("Full Migration", cursor.getString(1))
            assertEquals("https://example.com/full", cursor.getString(2))
        }
    }
}
