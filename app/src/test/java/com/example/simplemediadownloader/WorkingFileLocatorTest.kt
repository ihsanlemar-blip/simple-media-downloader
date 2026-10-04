package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkingFileLocatorTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `engine marker selects encoded output when input and output timestamps tie`() {
        val directory = temporary.newFolder()
        val input = directory.resolve("input.m4a").apply { writeBytes(byteArrayOf(1)) }
        val output = directory.resolve("output.mp3").apply { writeBytes(byteArrayOf(2)) }
        assertTrue(input.setLastModified(1_600_000_000_000L))
        assertTrue(output.setLastModified(1_600_000_000_000L))
        assertEquals(input.lastModified(), output.lastModified())
        assertEquals(output, WorkingFileLocator.find(directory, OkHttpDownloadEngine.OUTPUT_MARKER + output.absolutePath))
    }
}
