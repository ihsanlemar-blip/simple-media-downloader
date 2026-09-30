package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test
    fun `extractAudioTrack aborts immediately and deletes output when cancelled`() {
        val videoFile = tempFolder.newFile("source_video.mp4")
        val outputFile = tempFolder.newFile("extracted.m4a")

        val success = MediaStreamMuxer.extractAudioTrack(
            sourceFile = videoFile,
            outputFile = outputFile,
            isCancelled = { true },
        )

        assertFalse(success)
        assertFalse(outputFile.exists())
    }

    @Test
    fun `extractAudioTrack cleans output on invalid source file`() {
        val corruptedSource = tempFolder.newFile("corrupted.mp4")
        corruptedSource.writeBytes(ByteArray(50))
        val outputFile = tempFolder.newFile("audio_out.m4a")

        val success = MediaStreamMuxer.extractAudioTrack(
            sourceFile = corruptedSource,
            outputFile = outputFile,
            isCancelled = { false },
        )

        assertFalse(success)
        assertFalse(outputFile.exists())
    }

    @Test
    fun `codec compatibility validates MP4 supported video and audio codecs and rejects incompatible ones`() {
        // Supported MP4 video MIME types
        assertTrue(MediaStreamMuxer.isSupportedVideoMime("video/avc"))
        assertTrue(MediaStreamMuxer.isSupportedVideoMime("video/hevc"))
        assertTrue(MediaStreamMuxer.isSupportedVideoMime("video/mp4v-es"))
        assertTrue(MediaStreamMuxer.isSupportedVideoMime("video/3gpp"))
        assertTrue(MediaStreamMuxer.isSupportedVideoMime("video/av01"))

        // Incompatible video MIME types (belong to WebM/MKV)
        assertFalse(MediaStreamMuxer.isSupportedVideoMime("video/x-vnd.on2.vp8"))
        assertFalse(MediaStreamMuxer.isSupportedVideoMime("video/x-vnd.on2.vp9"))
        assertFalse(MediaStreamMuxer.isSupportedVideoMime("video/webm"))
        assertFalse(MediaStreamMuxer.isSupportedVideoMime(null))
        assertFalse(MediaStreamMuxer.isSupportedVideoMime(""))

        // Supported MP4 audio MIME types
        assertTrue(MediaStreamMuxer.isSupportedAudioMime("audio/mp4a-latm"))
        assertTrue(MediaStreamMuxer.isSupportedAudioMime("audio/opus"))
        assertTrue(MediaStreamMuxer.isSupportedAudioMime("audio/3gpp"))
        assertTrue(MediaStreamMuxer.isSupportedAudioMime("audio/amr-wb"))

        // Incompatible audio MIME types
        assertFalse(MediaStreamMuxer.isSupportedAudioMime("audio/vorbis"))
        assertFalse(MediaStreamMuxer.isSupportedAudioMime("audio/x-flac"))
        assertFalse(MediaStreamMuxer.isSupportedAudioMime("audio/flac"))
        assertFalse(MediaStreamMuxer.isSupportedAudioMime(null))
        assertFalse(MediaStreamMuxer.isSupportedAudioMime(""))
    }
}
