package com.kanzz.editor

import android.graphics.Bitmap
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** AI optical-flow motion blur used by the offline clip renderer only. */
@UnstableApi
class AiMotionBlurGlEffect(
    private val blur: Float,
    private val blend: Float,
    private val direction: Int,
    private val times: Int,
    private val modelFile: File,
    private val diagnostic: Boolean = false
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        blur <= 0.001f || blend <= 0.001f

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram =
        AiMotionBlurShaderProgram(context, useHdr, blur, blend, direction, times, modelFile, diagnostic)
}

@UnstableApi
private class AiMotionBlurShaderProgram(
    context: android.content.Context,
    useHdr: Boolean,
    private val blur: Float,
    private val blend: Float,
    private val direction: Int,
    private val times: Int,
    modelFile: File,
    private val diagnostic: Boolean
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(context, "shaders/vertex_shader.glsl", "shaders/motion_blur_ai.glsl")
    private val engine = NeuFlowV2AiEngine(modelFile)
    private var width = 1
    private var height = 1
    private var framebuffer = 0
    private var flowTexture = 0
    private var previousFrame: Bitmap? = null
    private var lastTimestampUs = Long.MIN_VALUE
    private val maxFlowPixels = 128f
    private var inferenceFrameCount = 0

    init {
        program.setBufferAttribute("aPosition", floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f), 2)
        program.setBufferAttribute("aTexSamplingCoord", floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f), 2)
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        framebuffer = fb[0]
        val tx = IntArray(1)
        GLES20.glGenTextures(1, tx, 0)
        flowTexture = tx[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (lastTimestampUs != Long.MIN_VALUE &&
                (presentationTimeUs <= lastTimestampUs || presentationTimeUs - lastTimestampUs > 250_000L)
            ) {
                previousFrame?.recycle()
                previousFrame = null
            }

            val current = readTexture(inputTexId)
            val previous = previousFrame
            if (previous != null) {
                val field = engine.estimate(previous, current)
                uploadFlow(field)
                inferenceFrameCount++
                if (inferenceFrameCount == 1 || inferenceFrameCount % 15 == 0) {
                    Log.i("CAPCAKE_AI_FLOW", "frame=" + inferenceFrameCount +
                        " timeUs=" + presentationTimeUs +
                        " mean=" + String.format(java.util.Locale.US, "%.3f", field.meanMagnitude) + "px" +
                        " max=" + String.format(java.util.Locale.US, "%.3f", field.maxMagnitude) + "px" +
                        " active=" + String.format(java.util.Locale.US, "%.1f", field.activeRatio * 100f) + "%" +
                        " infer=" + field.inferenceMs + "ms")
                }
            }
            previousFrame?.recycle()
            previousFrame = current
            lastTimestampUs = presentationTimeUs

            program.use()
            program.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            program.setSamplerTexIdUniform("uAiFlowTexture", flowTexture, 1)
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / width, 1f / height))
            program.setFloatUniform("uBlur", blur.coerceIn(0f, 1f))
            program.setFloatUniform("uBlend", blend.coerceIn(0f, 1f))
            program.setFloatUniform("uDirection", direction.coerceIn(0, 2).toFloat())
            program.setFloatUniform("uTimes", times.toFloat())
            program.setFloatUniform("uFlowReady", if (previous == null) 0f else 1f)
            program.setFloatUniform("uMaxFlowPixels", maxFlowPixels)
            program.setFloatUniform("uDiagnostic", if (diagnostic) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: VideoFrameProcessingException) {
            throw e
        } catch (e: Exception) {
            Log.e("CAPCAKE_AI_MOTION", "AI inference failed at $presentationTimeUs us", e)
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Read the current decoder texture for neural inference on the render worker. */
    private fun readTexture(textureId: Int): Bitmap {
        val oldFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer AI tidak lengkap"
        }
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
        buffer.rewind()

        // Preserve GL's bottom-up row order consistently for both input frames.
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Pack signed displacement into RGBA8 for the GLES2 smear shader. */
    private fun uploadFlow(field: AiMotionField) {
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        for (i in 0 until width * height) {
            val vx = field.x[i].coerceIn(-maxFlowPixels, maxFlowPixels)
            val vy = (-field.y[i]).coerceIn(-maxFlowPixels, maxFlowPixels)
            bytes.put(((vx / maxFlowPixels * 0.5f + 0.5f) * 255f).toInt().toByte())
            bytes.put(((vy / maxFlowPixels * 0.5f + 0.5f) * 255f).toInt().toByte())
            bytes.put(0)
            bytes.put(255.toByte())
        }
        bytes.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowTexture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun release() {
        try {
            super.release()
            previousFrame?.recycle()
            previousFrame = null
            if (flowTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(flowTexture), 0)
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            engine.close()
            program.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
