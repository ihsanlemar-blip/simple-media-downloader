package com.example.simplemediadownloader

import java.io.Closeable

/** Small CBR-only bridge. PCM is interleaved signed 16-bit mono or stereo. */
class Mp3EncoderBridge(sampleRate: Int, channels: Int, bitrateKbps: Int) : Closeable {
    private var handle: Long = nativeCreate(sampleRate, Mp3SampleRatePolicy.outputRate(sampleRate), channels, bitrateKbps)

    @Synchronized
    fun encodeInterleaved(pcm: ShortArray, samplesPerChannel: Int, output: ByteArray): Int {
        check(handle != 0L) { "MP3 encoder is closed" }
        return nativeEncode(handle, pcm, samplesPerChannel, output)
    }

    @Synchronized
    fun flush(output: ByteArray): Int {
        check(handle != 0L) { "MP3 encoder is closed" }
        return nativeFlush(handle, output)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int, outputRate: Int, channels: Int, bitrateKbps: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, samplesPerChannel: Int, output: ByteArray): Int
    private external fun nativeFlush(handle: Long, output: ByteArray): Int
    private external fun nativeClose(handle: Long)

    companion object {
        // Includes the worst supported resampling ratio: 8 kHz → 44.1 kHz.
        const val OUTPUT_BUFFER_BYTES = 8192 * 6 * 5 / 4 + 7200
        init { System.loadLibrary("smd_mp3") }
    }
}

/** LAME performs its own band-limited polyphase resampling when rates differ. */
internal object Mp3SampleRatePolicy {
    private val supported = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000)
    fun outputRate(inputRate: Int): Int {
        check(inputRate in supported) { "Unsupported MP3 input sample rate: $inputRate Hz" }
        return when (inputRate) {
            32000, 44100, 48000 -> inputRate
            96000 -> 48000
            else -> 44100
        }
    }
}
