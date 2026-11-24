package com.kanzz.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Gain processor untuk mengatur level audio per klip sampai 2x.
 * Dipakai pada preview audio/video; proses export memakai ChannelMixingAudioProcessor.
 */
@androidx.media3.common.util.UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var gain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val remaining = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val currentGain = gain.coerceIn(0f, 2f)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.remaining() >= 2) {
                    val sample = inputBuffer.short.toInt()
                    val amplified = (sample * currentGain).roundToInt().coerceIn(-32768, 32767)
                    outputBuffer.putShort(amplified.toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.remaining() >= 4) {
                    val sample = inputBuffer.float
                    outputBuffer.putFloat((sample * currentGain).coerceIn(-1f, 1f))
                }
            }
            else -> outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }
}
