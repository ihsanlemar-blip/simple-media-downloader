package com.example.simplemediadownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaStreamMuxerCancellationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mux aborts immediately and cleans output when cancelled before start`() {
        val videoFile = tempFolder.newFile("dummy_video.mp4")
        val audioFile = tempFolder.newFile("dummy_audio.m4a")
        val outputFile = tempFolder.newFile("output.mp4")

        val success = MediaStreamMuxer.mux(
            videoSource = videoFile,
            audioSource = audioFile,
            outputFile = outputFile,
            isCancelled = { true },
        )

        assertFalse(success)
        assertFalse("Output file must be deleted when cancelled", outputFile.exists())
    }

    @Test
    fun `mux cleans output file when input tracks are invalid`() {
        val videoFile = tempFolder.newFile("empty_video.mp4")
        val audioFile = tempFolder.newFile("empty_audio.m4a")
        val outputFile = tempFolder.newFile("corrupted_output.mp4")

        // Write non-media garbage into inputs
        videoFile.writeBytes(ByteArray(100))
        audioFile.writeBytes(ByteArray(100))

        val success = MediaStreamMuxer.mux(
            videoSource = videoFile,
            audioSource = audioFile,
            outputFile = outputFile,
            isCancelled = { false },
        )

        assertFalse(success)
        assertFalse("Output file must be deleted on mux failure", outputFile.exists())
    }

    @Test
    fun `mux deletes output file if exception is thrown during execution`() {
        val nonExistentVideo = tempFolder.root.resolve("non_existent_video.mp4")
        val nonExistentAudio = tempFolder.root.resolve("non_existent_audio.m4a")
        val outputFile = tempFolder.newFile("should_be_deleted.mp4")

        val success = MediaStreamMuxer.mux(
            videoSource = nonExistentVideo,
            audioSource = nonExistentAudio,
            outputFile = outputFile,
            isCancelled = { false },
        )

        assertFalse(success)
        assertFalse(outputFile.exists())
    }
}
