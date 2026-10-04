package com.example.simplemediadownloader

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp3FramesAndPcmTest {
    @Test fun `Layer III frame header accepts MPEG1 and rejects AAC or other layers`() {
        val mp3 = Mp3Frames.header(0xfffbb000.toInt())!!
        assertEquals(192, mp3.bitrateKbps)
        assertEquals(44100, mp3.sampleRate)
        assertEquals(626, mp3.size)
        assertNull(Mp3Frames.header(0xfff15080.toInt())) // AAC ADTS
        assertNull(Mp3Frames.header(0xfffd9000.toInt())) // Layer II
        assertNull(Mp3Frames.header(0xfffb0000.toInt())) // Free bitrate unsupported
        assertNull(Mp3Frames.header(0xfffbfc00.toInt())) // Invalid indices
    }
    @Test fun `float PCM clips safely including non-finite samples`() {
        val input = floatArrayOf(-2f, -1f, -.5f, 0f, .5f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        val expected = shortArrayOf(-32768, -32768, -16384, 0, 16384, 32767, 32767, 0, 32767)
        assertArrayEquals(expected, ShortArray(input.size) { Pcm16Normalizer.floatToShort(input[it]) })
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putShort(-32768).putShort(32767).putShort(-1).putShort(1)
        buffer.flip()
        val output = ShortArray(4)
        Pcm16Normalizer.read(buffer, output, 4, 2)
        assertArrayEquals(shortArrayOf(-32768,32767,-1,1), output)
    }
    @Test fun `sample rate policy avoids unnecessary resampling and enables MPEG1 bitrates`() {
        for (rate in listOf(32000,44100,48000)) assertEquals(rate, Mp3SampleRatePolicy.outputRate(rate))
        for (rate in listOf(8000,11025,12000,16000,22050,24000,88200)) assertEquals(44100, Mp3SampleRatePolicy.outputRate(rate))
        assertEquals(48000, Mp3SampleRatePolicy.outputRate(96000))
    }
    @Test fun `unsupported layout and rates fail explicitly`() {
        for (channels in listOf(0, 3, 6)) {
            assertThrows(IllegalStateException::class.java) { Pcm16Normalizer.requireSupported(2, channels, 44100) }
        }
        assertThrows(IllegalStateException::class.java) { Pcm16Normalizer.requireSupported(2, 2, 12345) }
        for (rate in listOf(32000,44100,48000)) for (channels in 1..2) Pcm16Normalizer.requireSupported(2, channels, rate)
    }
}
