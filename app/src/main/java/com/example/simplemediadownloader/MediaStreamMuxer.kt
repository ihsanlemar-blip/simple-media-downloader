package com.example.simplemediadownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

object MediaStreamMuxer {

    val SUPPORTED_MP4_VIDEO_MIMES = setOf(
        "video/avc",
        "video/hevc",
        "video/mp4v-es",
        "video/3gpp",
        "video/av01",
        "video/dolby-vision",
    )

    val SUPPORTED_MP4_AUDIO_MIMES = setOf(
        "audio/mp4a-latm",
        "audio/3gpp",
        "audio/amr-wb",
        "audio/opus",
    )

    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024
    private const val MAX_DYNAMIC_BUFFER_SIZE = 32 * 1024 * 1024

    private class DynamicBuffer(initialCapacity: Int) {
        var buffer: ByteBuffer = ByteBuffer.allocate(initialCapacity.coerceIn(64 * 1024, MAX_DYNAMIC_BUFFER_SIZE))

        fun readSample(extractor: MediaExtractor): Int {
            while (true) {
                try {
                    buffer.clear()
                    return extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    if (buffer.capacity() >= MAX_DYNAMIC_BUFFER_SIZE) throw e
                    buffer = ByteBuffer.allocate((buffer.capacity() * 2).coerceAtMost(MAX_DYNAMIC_BUFFER_SIZE))
                }
            }
        }
    }

    private fun getInitialBufferSize(format: MediaFormat?): Int {
        if (format == null) return DEFAULT_BUFFER_SIZE
        return try {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val maxInput = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (maxInput > 0) maxOf(maxInput, DEFAULT_BUFFER_SIZE) else DEFAULT_BUFFER_SIZE
            } else {
                DEFAULT_BUFFER_SIZE
            }
        } catch (_: Exception) {
            DEFAULT_BUFFER_SIZE
        }
    }

    fun isSupportedVideoMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        return mime.lowercase(Locale.US) in SUPPORTED_MP4_VIDEO_MIMES
    }

    fun isSupportedAudioMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        return mime.lowercase(Locale.US) in SUPPORTED_MP4_AUDIO_MIMES
    }

    fun mux(
        videoSource: File,
        audioSource: File,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false

        return try {
            if (isCancelled()) {
                outputFile.delete()
                return false
            }

            videoExtractor.setDataSource(videoSource.absolutePath)
            audioExtractor.setDataSource(audioSource.absolutePath)

            var videoTrackIndex = -1
            var videoSourceTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    if (!isSupportedVideoMime(mime)) {
                        outputFile.delete()
                        return false
                    }
                    videoFormat = format
                    videoSourceTrack = i
                    break
                }
            }

            var audioTrackIndex = -1
            var audioSourceTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    if (!isSupportedAudioMime(mime)) {
                        outputFile.delete()
                        return false
                    }
                    audioFormat = format
                    audioSourceTrack = i
                    break
                }
            }

            if (videoSourceTrack < 0 || audioSourceTrack < 0 || videoFormat == null || audioFormat == null) {
                outputFile.delete()
                return false
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = muxer.addTrack(videoFormat)
            audioTrackIndex = muxer.addTrack(audioFormat)

            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.selectTrack(audioSourceTrack)

            muxer.start()
            isMuxerStarted = true

            val initialCapacity = maxOf(getInitialBufferSize(videoFormat), getInitialBufferSize(audioFormat))
            val dynamicBuffer = DynamicBuffer(initialCapacity)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = false

            while (!videoDone || !audioDone) {
                if (isCancelled()) {
                    outputFile.delete()
                    return false
                }

                val videoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoDone && videoTime < 0L) videoDone = true
                if (!audioDone && audioTime < 0L) audioDone = true

                if (videoDone && audioDone) break

                if (!videoDone && (audioDone || videoTime <= audioTime)) {
                    bufferInfo.offset = 0
                    bufferInfo.size = dynamicBuffer.readSample(videoExtractor)
                    if (bufferInfo.size < 0) {
                        videoDone = true
                    } else {
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        muxer.writeSampleData(videoTrackIndex, dynamicBuffer.buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    bufferInfo.offset = 0
                    bufferInfo.size = dynamicBuffer.readSample(audioExtractor)
                    if (bufferInfo.size < 0) {
                        audioDone = true
                    } else {
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = if ((audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        muxer.writeSampleData(audioTrackIndex, dynamicBuffer.buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            if (isCancelled()) {
                outputFile.delete()
                return false
            }

            muxer.stop()
            isMuxerStarted = false
            true
        } catch (_: Exception) {
            outputFile.delete()
            false
        } finally {
            try {
                if (isMuxerStarted) {
                    runCatching { muxer?.stop() }
                }
            } finally {
                runCatching { muxer?.release() }
                runCatching { videoExtractor.release() }
                runCatching { audioExtractor.release() }
            }
        }
    }

    fun extractAudioTrack(
        sourceFile: File,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false

        return try {
            if (isCancelled()) {
                outputFile.delete()
                return false
            }

            extractor.setDataSource(sourceFile.absolutePath)

            var audioSourceTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    if (!isSupportedAudioMime(mime)) {
                        outputFile.delete()
                        return false
                    }
                    audioFormat = format
                    audioSourceTrack = i
                    break
                }
            }

            if (audioSourceTrack < 0 || audioFormat == null) {
                outputFile.delete()
                return false
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val audioTrackIndex = muxer.addTrack(audioFormat)

            extractor.selectTrack(audioSourceTrack)
            muxer.start()
            isMuxerStarted = true

            val initialCapacity = getInitialBufferSize(audioFormat)
            val dynamicBuffer = DynamicBuffer(initialCapacity)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) {
                    outputFile.delete()
                    return false
                }
                bufferInfo.offset = 0
                bufferInfo.size = dynamicBuffer.readSample(extractor)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(audioTrackIndex, dynamicBuffer.buffer, bufferInfo)
                extractor.advance()
            }

            if (isCancelled()) {
                outputFile.delete()
                return false
            }

            muxer.stop()
            isMuxerStarted = false
            true
        } catch (_: Exception) {
            outputFile.delete()
            false
        } finally {
            try {
                if (isMuxerStarted) {
                    runCatching { muxer?.stop() }
                }
            } finally {
                runCatching { muxer?.release() }
                runCatching { extractor.release() }
            }
        }
    }
}
