package com.kanzz.editor

import android.content.Context
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

/** Live parameter state for preview. direction: 0=both, 1=backward, 2=forward. */
class MotionBlurPreviewState {
    @Volatile var blur: Float = 0f
    @Volatile var blend: Float = 0.65f
    @Volatile var direction: Int = 0
    @Volatile var times: Int = 4
}

@UnstableApi
class MotionBlurGlEffect(
    private val blur: Float,
    private val blend: Float = 0.65f,
    private val direction: Int = 0,
    private val times: Int = 4,
    private val previewState: MotionBlurPreviewState? = null
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        previewState == null && (blur <= 0.001f || blend <= 0.001f)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MotionBlurShaderProgram(context, useHdr, blur, blend, direction, times, previewState)
}

@UnstableApi
private class MotionBlurShaderProgram(
    context: Context,
    useHdr: Boolean,
    blur: Float,
    blend: Float,
    direction: Int,
    times: Int,
    private val previewState: MotionBlurPreviewState?
) : BaseGlShaderProgram(useHdr, 1) {
    private val glProgram = GlProgram(context, "shaders/vertex_shader.glsl", "shaders/motion_blur.glsl")
    private val fixedBlur = blur.coerceIn(0f, 1f)
    private val fixedBlend = blend.coerceIn(0f, 1f)
    private val fixedDirection = direction.coerceIn(0, 2)
    private val fixedTimes = times
    private var frameWidth = 1
    private var frameHeight = 1
    private val historyTextures = IntArray(6)
    private var historyFramebuffer = 0
    private var historyWriteIndex = 0
    private var historyCount = 0
    private var lastPresentationTimeUs = Long.MIN_VALUE

    init {
        glProgram.setBufferAttribute("aPosition", floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), 2)
        glProgram.setBufferAttribute("aTexSamplingCoord", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2)
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth.coerceAtLeast(1)
        frameHeight = inputHeight.coerceAtLeast(1)
        releaseHistory()
        GLES20.glGenTextures(historyTextures.size, historyTextures, 0)
        historyTextures.forEach { texture ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        historyFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Size(frameWidth, frameHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (lastPresentationTimeUs != Long.MIN_VALUE &&
                (presentationTimeUs <= lastPresentationTimeUs || presentationTimeUs - lastPresentationTimeUs > 250_000L)
            ) {
                historyCount = 0
                historyWriteIndex = 0
            }
            val amount = (previewState?.blur ?: fixedBlur).coerceIn(0f, 1f)
            val mixAmount = (previewState?.blend ?: fixedBlend).coerceIn(0f, 1f)
            val directionValue = (previewState?.direction ?: fixedDirection).coerceIn(0, 2)
            val sampleCount = (previewState?.times ?: fixedTimes).let {
                if (it == 1 || it == 2 || it == 4 || it == 6) it else 4
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            // Only uHistory0 is active in the current streak shader.
            // Binding optimized-out uniforms can fail on some GLES drivers.
            val previousTexture = if (historyCount > 0) {
                historyTextures[(historyWriteIndex - 1 + historyTextures.size) % historyTextures.size]
            } else historyTextures[0]
            glProgram.setSamplerTexIdUniform("uHistory0", previousTexture, 1)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / frameWidth, 1f / frameHeight))
            glProgram.setFloatUniform("uBlur", amount)
            glProgram.setFloatUniform("uBlend", mixAmount)
            glProgram.setFloatUniform("uDirection", directionValue.toFloat())
            glProgram.setFloatUniform("uTimes", sampleCount.toFloat())
            glProgram.setFloatUniform("uHistoryCount", historyCount.toFloat())
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            captureInputFrame(inputTexId)
            lastPresentationTimeUs = presentationTimeUs
        } catch (e: VideoFrameProcessingException) {
            Log.e("CAPCAKE_MOTION_BLUR", "VideoFrameProcessingException at $presentationTimeUs us", e)
            throw e
        } catch (e: Exception) {
            Log.e("CAPCAKE_MOTION_BLUR", "drawFrame failed at $presentationTimeUs us", e)
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun captureInputFrame(inputTexId: Int) {
        if (historyFramebuffer == 0 || historyTextures[historyWriteIndex] == 0) return
        val oldFramebuffer = IntArray(1)
        val oldTexture = IntArray(1)
        val oldActiveTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, oldActiveTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, oldTexture, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTextures[historyWriteIndex])
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, frameWidth, frameHeight)
            historyWriteIndex = (historyWriteIndex + 1) % historyTextures.size
            historyCount = (historyCount + 1).coerceAtMost(historyTextures.size)
        } else {
            historyCount = 0
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldTexture[0])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
        GLES20.glActiveTexture(oldActiveTexture[0])
    }

    private fun releaseHistory() {
        if (historyTextures.any { it != 0 }) {
            GLES20.glDeleteTextures(historyTextures.size, historyTextures, 0)
            historyTextures.fill(0)
        }
        if (historyFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(historyFramebuffer), 0)
            historyFramebuffer = 0
        }
        historyWriteIndex = 0
        historyCount = 0
        lastPresentationTimeUs = Long.MIN_VALUE
    }

    override fun release() {
        try {
            super.release()
            releaseHistory()
            glProgram.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
