package com.example.simplemediadownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class Mp3ConversionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun workspace(): File = File(context.cacheDir, "mp3-test-${System.nanoTime()}").apply { mkdirs() }
    private fun fixture(name: String, directory: File): File = File(directory, name).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/$name").use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }
    private fun assertDecodable(file: File, durationSeconds: Double = 2.0) {
        assertTrue(file.length() > 0)
        Mp3Validation.validate(file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mpeg", format.getString(MediaFormat.KEY_MIME))
            assertEquals(durationSeconds, format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0, .3)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType("audio/mpeg")
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var decoded = false
                var eos = false
                val started = System.nanoTime()
                while (!decoded && System.nanoTime() - started < 10_000_000_000L) {
                    if (!eos) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(index))
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eos = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index >= 0) {
                        decoded = info.size > 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                assertTrue("Output must decode to PCM", decoded)
            } finally { runCatching { decoder.stop() }; decoder.release() }
        } finally { extractor.release() }
    }
    @Test fun jniLameSmokeTestAndDoubleClose() {
        val dir = workspace()
        try {
            for (rate in listOf(8000, 22050, 32000, 44100, 48000, 96000)) for (channels in 1..2) for (bitrate in PlatformAudioPolicy.MP3_BITRATES) {
                val output = File(dir, "$rate-$channels-$bitrate.mp3")
                val encoder = Mp3EncoderBridge(rate, channels, bitrate)
                try {
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(Mp3EncoderBridge.OUTPUT_BUFFER_BYTES)
                        val pcm = ShortArray(8192 * channels)
                        var position = 0
                        while (position < rate) {
                            val frames = minOf(8192, rate - position)
                            for (i in 0 until frames) for (channel in 0 until channels) {
                                pcm[i * channels + channel] = (sin(2 * Math.PI * 440 * (position + i) / rate) * 16000).toInt().toShort()
                            }
                            val bytes = encoder.encodeInterleaved(pcm, frames, buffer)
                            stream.write(buffer, 0, bytes)
                            position += frames
                        }
                        val bytes = encoder.flush(buffer)
                        stream.write(buffer, 0, bytes)
                    }
                } finally { encoder.close(); encoder.close() }
                assertEquals(bitrate, Mp3Validation.validate(output))
                assertDecodable(output, 1.0)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun jniRejectsInvalidParametersAndClosedHandlesWithoutCrashing() {
        assertThrows(IllegalStateException::class.java) { Mp3EncoderBridge(44100, 6, 192) }
        assertThrows(IllegalStateException::class.java) { Mp3EncoderBridge(44100, 2, 200) }
        val encoder = Mp3EncoderBridge(44100, 2, 192)
        try {
            assertThrows(IllegalStateException::class.java) { encoder.encodeInterleaved(ShortArray(1), 8192, ByteArray(Mp3EncoderBridge.OUTPUT_BUFFER_BYTES)) }
            assertThrows(IllegalStateException::class.java) { encoder.encodeInterleaved(ShortArray(200), 100, ByteArray(1)) }
        } finally { encoder.close(); encoder.close() }
        assertThrows(IllegalStateException::class.java) { encoder.flush(ByteArray(Mp3EncoderBridge.OUTPUT_BUFFER_BYTES)) }
    }

    @Test fun downloadedAacOpusAndVideoAudioConvertAtAllBitrates() = runBlocking {
        val dir = workspace()
        try {
            for (name in listOf("tone.m4a", "tone.aac", "tone.webm", "tone.mp4", "tone.mp3")) {
                val source = fixture(name, dir)
                for (bitrate in PlatformAudioPolicy.MP3_BITRATES) {
                    val output = File(dir, "$name-$bitrate.mp3")
                    val progress = mutableListOf<Float?>()
                    Mp3AudioTranscoder().transcode(source, output, bitrate, { false }) { progress.add(it) }
                    assertDecodable(output)
                    assertEquals(bitrate, Mp3Validation.validate(output))
                    assertEquals(100f, progress.last())
                    assertFalse(File(dir, "${output.name}.part").exists())
                    if (name == "tone.mp3" && bitrate == 192) assertArrayEquals(source.readBytes(), output.readBytes())
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun renamedAacM4aAndWebmFailStrictMp3Validation() {
        val dir = workspace()
        try {
            for (name in listOf("tone.aac", "tone.m4a", "tone.webm")) {
                val source = fixture(name, dir)
                val renamed = File(dir, "$name.mp3")
                source.copyTo(renamed)
                assertThrows(IllegalStateException::class.java) { Mp3Validation.validate(renamed) }
            }
            assertDecodable(fixture("tone.mp3", dir))
        } finally { dir.deleteRecursively() }
    }
    @Test fun engineDownloadsFullyBeforeConversionAndCleansSourceOnCancel() = runBlocking {
        val dir = workspace()
        try {
            val payload = fixture("tone.m4a", dir).readBytes()
            dir.listFiles()!!.forEach { it.delete() }
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Length", payload.size.toString())
                    .body(okhttp3.ResponseBody.create(null, payload)).build()
            }.build()
            val format = AudioFormatOptions.mp3(AvailableFormat("aac", DownloadMode.AUDIO_ORIGINAL,
                "https://example.test/audio", extension = "m4a"), 192, 2)
            val engine = OkHttpDownloadEngine(client = client, enforceSecurityPolicy = false)
            val states = mutableListOf<DownloadState>()
            val request = DownloadRequest("success", "https://example.test/watch", "Tone", format, batchId = "batch-device", batchIndex = 0, sourceItemId = "tone")
            val result = engine.download(request, dir) { states.add(it) }
            assertTrue(result is DownloadExecutionResult.Success)
            val firstConversion = states.indexOfFirst { it is DownloadState.Converting }
            assertTrue(firstConversion > 0)
            assertTrue(states.take(firstConversion).any { it is DownloadState.Downloading && it.progress.percentage == 100f })
            assertFalse(states.any { it is DownloadState.Completed })
            assertEquals(listOf("Tone.mp3"), dir.listFiles()!!.map { it.name })
            assertDecodable(File(dir, "Tone.mp3"))
            dir.listFiles()!!.forEach { it.delete() }
            val nativeStates = mutableListOf<DownloadState>()
            val nativeResult = engine.download(request.copy(id = "native-child", format = format.copy(
                mode = DownloadMode.AUDIO_ORIGINAL, extension = "m4a", targetAudioBitrateKbps = 0)), dir) { nativeStates.add(it) }
            assertTrue(nativeResult is DownloadExecutionResult.Success)
            assertFalse(nativeStates.any { it is DownloadState.Converting })
            assertArrayEquals(payload, File(dir, "Tone.m4a").readBytes())
            dir.listFiles()!!.forEach { it.delete() }
            val cancelled = engine.download(request.copy(id = "cancel"), dir) {
                if (it is DownloadState.Converting && (it.progress.percentage ?: 0f) >= 20f) {
                    runBlocking { engine.cancel("cancel") }
                }
            }
            assertEquals(DownloadExecutionResult.Cancelled, cancelled)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationDuringConversionDeletesPartAndReleasesEncoder() = runBlocking {
        val dir = workspace()
        try {
            val source = fixture("tone.m4a", dir)
            val output = File(dir, "cancelled.mp3")
            var cancelled = false
            var caught = false
            try {
                Mp3AudioTranscoder().transcode(source, output, 192, { cancelled }) {
                    if ((it ?: 0f) >= 20f) cancelled = true
                }
            } catch (_: CancellationException) { caught = true }
            assertTrue(caught)
            assertFalse(output.exists())
            assertFalse(File(dir, "cancelled.mp3.part").exists())
            // A subsequent encode succeeds: codec/native/semaphore lifecycle was released.
            Mp3AudioTranscoder().transcode(source, output, 192, { false }) {}
            assertDecodable(output)
        } finally { dir.deleteRecursively() }
    }

    @Test fun validatedMp3ExportsToMusicWithCorrectMimeAndNoPendingRow() = runBlocking {
        val dir = workspace()
        val exporter = DownloadsStorageExporter(context, workspaceRoot = dir)
        val format = AudioFormatOptions.mp3(AvailableFormat("aac", DownloadMode.AUDIO_ORIGINAL,
            "https://example.test/audio", extension = "m4a"), 192, 2)
        val request = DownloadRequest("export-${System.nanoTime()}", "https://example.test/watch", "Tone", format, batchId = "batch-device", batchIndex = 0, filenamePrefix = "01 - ")
        val destination = exporter.prepareDestination(request).getOrThrow()
        var exported: DownloadOutput? = null
        try {
            val source = fixture("tone.m4a", destination.directory)
            val output = File(destination.directory, "Tone.mp3")
            Mp3AudioTranscoder().transcode(source, output, 192, { false }) {}
            exported = exporter.exportCompletedFile(request, destination, OkHttpDownloadEngine.OUTPUT_MARKER + output.absolutePath).getOrThrow()
            assertEquals("audio/mpeg", exported.mimeType)
            assertEquals("01 - Tone.mp3", exported.displayName)
            context.contentResolver.query(Uri.parse(exported.contentUri), arrayOf(
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("audio/mpeg", cursor.getString(0))
                assertEquals("Music/MediaDownloader/", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
            val saved = File(destination.directory, "saved.mp3")
            context.contentResolver.openInputStream(Uri.parse(exported.contentUri))!!.use { input ->
                saved.outputStream().use { input.copyTo(it) }
            }
            assertDecodable(saved)
        } finally {
            exported?.let { exporter.deleteOutput(it) }
            exporter.cleanup(destination)
            dir.deleteRecursively()
        }
    }
}
