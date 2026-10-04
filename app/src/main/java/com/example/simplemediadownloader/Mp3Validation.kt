package com.example.simplemediadownloader

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/** MPEG Layer III header parser; the layer bits deliberately reject AAC ADTS. */
internal object Mp3Frames {
    data class Header(val size: Int, val bitrateKbps: Int, val sampleRate: Int)
    fun header(bits: Int): Header? {
        if ((bits ushr 21) != 0x7ff) return null
        val version = (bits ushr 19) and 3
        if (version == 1 || (bits ushr 17) and 3 != 1) return null
        val bitrateIndex = (bits ushr 12) and 15
        val rateIndex = (bits ushr 10) and 3
        if (bitrateIndex !in 1..14 || rateIndex == 3) return null
        val bitrate = if (version == 3) MPEG1[bitrateIndex] else MPEG2[bitrateIndex]
        val rate = intArrayOf(44100, 48000, 32000)[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val size = (if (version == 3) 144000 else 72000) * bitrate / rate + ((bits ushr 9) and 1)
        return Header(size, bitrate, rate)
    }
    private val MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Full bounded-memory structural validation, including CBR match for direct-copy optimization. */
    fun inspect(file: File, checkCancelled: () -> Unit = {}): Int? = RandomAccessFile(file, "r").use { input ->
        var offset = 0L
        if (input.length() < 10) return null
        val tag = ByteArray(10)
        input.readFully(tag)
        if (tag[0] == 73.toByte() && tag[1] == 68.toByte() && tag[2] == 51.toByte()) {
            if ((6..9).any { tag[it].toInt() and 0x80 != 0 }) return null
            var size = 0L
            for (i in 6..9) size = (size shl 7) or (tag[i].toLong() and 0x7f)
            offset = 10 + size + if (tag[5].toInt() and 0x10 != 0) 10 else 0
        }
        var frames = 0
        var bitrate = 0
        var variable = false
        while (offset + 4 <= input.length()) {
            checkCancelled()
            input.seek(offset)
            // Optional ID3v1 trailer.
            if (input.length() - offset == 128L && input.readUnsignedByte() == 84 &&
                input.readUnsignedByte() == 65 && input.readUnsignedByte() == 71) {
                offset = input.length(); break
            }
            input.seek(offset)
            val header = header(input.readInt()) ?: return null
            if (offset + header.size > input.length()) return null
            if (frames == 0) bitrate = header.bitrateKbps else if (bitrate != header.bitrateKbps) variable = true
            frames++
            offset += header.size
        }
        if (frames < 2 || offset != input.length()) null else if (variable) 0 else bitrate
    }
}

internal object Mp3Validation {
    fun validate(file: File, checkCancelled: () -> Unit = {}): Int {
        checkCancelled()
        val bitrate = Mp3Frames.inspect(file, checkCancelled)
            ?: error("Invalid MPEG Layer III frame structure")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            checkCancelled()
            check((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg"
            }) { "Final output is not recognized as audio/mpeg" }
        } finally {
            extractor.release()
        }
        checkCancelled()
        return bitrate
    }
}
