package com.kanzz.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes the source audio in the background and creates a compact amplitude
 * envelope for drawing inside timeline clips.
 */
object AudioWaveformDecoder {
    private const val BAR_COUNT = 180
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val pending = ConcurrentHashMap<String, MutableList<(FloatArray) -> Unit>>()

    fun request(context: Context, clip: AudioClip, callback: (FloatArray) -> Unit) {
        val key = "${clip.filePath}|${clip.sourceStartMs}|${clip.durationMs}"
        cache[key]?.let {
            callback(it)
            return
        }

        var shouldStart = false
        synchronized(pending) {
            val listeners = pending[key]
            if (listeners != null) {
                listeners.add(callback)
            } else {
                pending[key] = mutableListOf(callback)
                shouldStart = true
            }
        }
        if (!shouldStart) return

        val appContext = context.applicationContext
        executor.execute {
            val values = decode(appContext, clip)
            cache[key] = values
            val listeners = synchronized(pending) { pending.remove(key)?.toList().orEmpty() }
            listeners.forEach { listener ->
                try { listener(values) } catch (_: Exception) {}
            }
        }
    }

    private fun decode(context: Context, clip: AudioClip): FloatArray {
        val peaks = FloatArray(BAR_COUNT)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(clip.filePath), null)

            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return peaks
            extractor.selectTrack(audioTrack)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return peaks
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            val sourceEnd = clip.sourceStartMs + clip.durationMs.coerceAtLeast(1L)

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                        } else {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val timeUs = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null) {
                                val presentationMs = info.presentationTimeUs / 1000L
                                if (presentationMs >= clip.sourceStartMs && presentationMs < sourceEnd) {
                                    val bucket = (((presentationMs - clip.sourceStartMs).toDouble() /
                                        clip.durationMs.coerceAtLeast(1L)) * BAR_COUNT)
                                        .toInt().coerceIn(0, BAR_COUNT - 1)
                                    val data = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    val start = info.offset
                                    val end = (info.offset + info.size).coerceAtMost(data.capacity())
                                    var peak = 0f
                                    if (pcmFloat) {
                                        var p = start
                                        while (p + 3 < end) {
                                            peak = max(peak, abs(data.getFloat(p)).coerceAtMost(1f))
                                            p += 4
                                        }
                                    } else {
                                        var p = start
                                        while (p + 1 < end) {
                                            peak = max(peak, abs(data.getShort(p).toInt()) / 32768f)
                                            p += 2
                                        }
                                    }
                                    peaks[bucket] = max(peaks[bucket], peak)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        pcmFloat = try {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        } catch (_: Exception) { false }
                    }
                }
            }

            val maximum = peaks.maxOrNull() ?: 0f
            if (maximum > 0f) {
                for (i in peaks.indices) peaks[i] = (peaks[i] / maximum).coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            // Keep an empty waveform if the device codec cannot decode this format.
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
        return peaks
    }
}
