package com.example.simplemediadownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object MediaStreamMuxer {
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

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrackIndex = -1
            var videoSourceTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = muxer.addTrack(format)
                    videoSourceTrack = i
                    break
                }
            }

            var audioTrackIndex = -1
            var audioSourceTrack = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = muxer.addTrack(format)
                    audioSourceTrack = i
                    break
                }
            }

            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                outputFile.delete()
                return false
            }

            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.selectTrack(audioSourceTrack)

            muxer.start()
            isMuxerStarted = true

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
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
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoDone = true
                    } else {
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        audioDone = true
                    } else {
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = if ((audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
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
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var audioTrackIndex = -1
            var audioSourceTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = muxer.addTrack(format)
                    audioSourceTrack = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                outputFile.delete()
                return false
            }

            extractor.selectTrack(audioSourceTrack)
            muxer.start()
            isMuxerStarted = true

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) {
                    outputFile.delete()
                    return false
                }
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
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
