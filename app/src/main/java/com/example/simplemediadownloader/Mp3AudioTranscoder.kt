package com.example.simplemediadownloader

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One platform-independent local-file converter. No network access and no video decoder. */
class Mp3AudioTranscoder(private val dispatchers: AppDispatchers = AppDispatchers()) {
    suspend fun transcode(
        source: File,
        output: File,
        bitrateKbps: Int,
        isCancelled: () -> Boolean,
        onProgress: (Float?) -> Unit,
    ) = withContext(dispatchers.io) {
        val context = currentCoroutineContext()
        val checkCancelled = {
            context.ensureActive()
            if (isCancelled()) throw CancellationException("MP3 conversion cancelled")
        }
        checkCancelled()
        require(bitrateKbps in PlatformAudioPolicy.MP3_BITRATES)
        onProgress(0f)
        // Poll while waiting too: explicit task cancellation must not wait for another long encode.
        while (!conversionSlots.tryAcquire()) {
            checkCancelled()
            kotlinx.coroutines.delay(50)
        }
        try {
            checkCancelled()
            try {
                convert(source, output, bitrateKbps, checkCancelled, onProgress)
            } catch (error: LinkageError) {
                throw IllegalStateException("MP3 encoder native library is unavailable", error)
            }
        } finally {
            conversionSlots.release()
        }
    }

    private fun convert(
        source: File,
        output: File,
        bitrateKbps: Int,
        checkCancelled: () -> Unit,
        onProgress: (Float?) -> Unit,
    ) {
        val part = File(output.parentFile, "${output.name}.part")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: Mp3EncoderBridge? = null
        var decoderStarted = false
        var completed = false
        try {
            check(source.isFile && source.length() > 0) { "Downloaded audio source is empty" }
            extractor.setDataSource(source.absolutePath)
            checkCancelled()
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Downloaded source has no usable audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
            val estimate = Mp3SizeEstimator.bytes(durationUs?.let { (it + 999999) / 1000000 }, bitrateKbps)
                ?: StorageCapacityPolicy.DEFAULT_AUDIO_RESERVE_BYTES
            check(StatFs(source.parentFile!!.absolutePath).availableBytes >= StorageCapacityPolicy.requiredBytes(estimate, false)) {
                "Not enough available storage for MP3 conversion and export"
            }
            // A genuine CBR MP3 already at the target bitrate can be copied losslessly.
            if (mime == "audio/mpeg" && Mp3Validation.validate(source, checkCancelled) == bitrateKbps) {
                source.inputStream().buffered().use { input ->
                    part.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } else {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val codec = MediaCodec.createDecoderByType(mime)
                decoder = codec
                checkCancelled()
                codec.configure(format, null, null, 0)
                checkCancelled()
                codec.start()
                decoderStarted = true
                val info = MediaCodec.BufferInfo()
                val pcm = ShortArray(8192 * 2)
                val encoded = ByteArray(Mp3EncoderBridge.OUTPUT_BUFFER_BYTES)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var inputEos = false
                var outputEos = false
                var lastProgress = -1f
                var lastActivity = System.nanoTime()
                part.outputStream().buffered(64 * 1024).use { out ->
                    while (!outputEos) {
                        checkCancelled()
                        check(System.nanoTime() - lastActivity < 30_000_000_000L) { "Audio decoder stalled" }
                        if (!inputEos) {
                            val index = codec.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val input = requireNotNull(codec.getInputBuffer(index))
                                val size = extractor.readSampleData(input, 0)
                                checkCancelled()
                                if (size < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags)
                                    extractor.advance()
                                }
                                lastActivity = System.nanoTime()
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, 10000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val decoded = codec.outputFormat
                            val newChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val newRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            check(encoder == null || (newChannels == channels && newRate == rate)) { "Audio layout changed during conversion" }
                            channels = newChannels
                            rate = newRate
                            encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            Pcm16Normalizer.requireSupported(encoding, channels, rate)
                            lastActivity = System.nanoTime()
                        } else if (index >= 0) {
                            try {
                                checkCancelled()
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    Pcm16Normalizer.requireSupported(encoding, channels, rate)
                                    if (encoder == null) encoder = Mp3EncoderBridge(rate, channels, bitrateKbps)
                                    val decoded = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                                    decoded.position(info.offset)
                                    decoded.limit(info.offset + info.size)
                                    val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    check(info.size % frameBytes == 0) { "Incomplete PCM audio frame" }
                                    while (decoded.hasRemaining()) {
                                        checkCancelled()
                                        val frames = minOf(decoded.remaining() / frameBytes, 8192)
                                        Pcm16Normalizer.read(decoded, pcm, frames * channels, encoding)
                                        checkCancelled()
                                        val bytes = encoder!!.encodeInterleaved(pcm, frames, encoded)
                                        check(bytes >= 0) { "MP3 encoding failed" }
                                        out.write(encoded, 0, bytes)
                                    }
                                    val progress = durationUs?.let { (info.presentationTimeUs.toDouble() / it * 100).toFloat().coerceIn(0f, 99f) }
                                    if (progress == null || progress - lastProgress >= 1f) {
                                        onProgress(progress)
                                        if (progress != null) lastProgress = progress
                                    }
                                }
                                outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastActivity = System.nanoTime()
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                    checkCancelled()
                    val bytes = (encoder ?: error("Decoder produced no PCM audio")).flush(encoded)
                    checkCancelled()
                    out.write(encoded, 0, bytes)
                }
            }
            checkCancelled()
            Mp3Validation.validate(part, checkCancelled)
            checkCancelled()
            check(part.renameTo(output)) { "Could not finalize MP3 output" }
            checkCancelled()
            onProgress(100f)
            completed = true
        } finally {
            runCatching { encoder?.close() }
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            part.delete()
            if (!completed) output.delete()
        }
    }

    companion object { private val conversionSlots = Semaphore(1) }
}

internal object Pcm16Normalizer {
    fun requireSupported(encoding: Int, channels: Int, rate: Int) {
        check(channels in 1..2) { "Unsupported audio channel layout: $channels channels (mono/stereo only)" }
        Mp3SampleRatePolicy.outputRate(rate)
        check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported PCM encoding: $encoding" }
    }
    fun floatToShort(value: Float): Short = when {
        value.isNaN() -> 0
        value >= 1f -> Short.MAX_VALUE
        value <= -1f -> Short.MIN_VALUE
        else -> (value * 32768f).toInt().coerceIn(-32768, 32767).toShort()
    }
    fun read(input: ByteBuffer, output: ShortArray, samples: Int, encoding: Int) {
        for (i in 0 until samples) output[i] = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) floatToShort(input.float) else input.short
    }
}
