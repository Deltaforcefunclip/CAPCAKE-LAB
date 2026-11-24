package com.kanzz.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Nilai preview diperbarui dari UI thread dan dibaca oleh GL thread.
 */
class DreamyPreviewState {
    @Volatile var intensity: Float = 0f
    @Volatile var glowSpread: Float = 0.35f
    @Volatile var warmth: Float = 0.5f
    @Volatile var softFocus: Float = 0.35f
    @Volatile var effectMode: Float = 0f
    @Volatile var colorGrading: ColorGrading = ColorGrading()
    @Volatile var pullInZoom: Float = 1f
    @Volatile var pullInBlur: Float = 0f
    @Volatile var pullInFade: Float = 0f
}

/**
 * GPU Dreamy effect untuk Transformer dan ExoPlayer preview.
 * previewState non-null berarti parameter mengikuti playhead secara live.
 */
@UnstableApi
class DreamyGlEffect(
    private val intensity: Float,
    private val glowSpread: Float,
    private val warmth: Float,
    private val startTimeUs: Long = 0L,
    private val endTimeUs: Long = Long.MAX_VALUE,
    private val previewState: DreamyPreviewState? = null,
    private val effectMode: Float = 0f,
    private val softFocus: Float = 0.35f,
    private val colorGrading: ColorGrading = ColorGrading(),
    private val pullInStartUs: Long = -1L,
    private val pullInEndUs: Long = -1L,
    private val pullInFadeIn: Boolean = false
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        if (previewState != null) false else (intensity <= 0.001f && colorGrading.isDefault && pullInEndUs <= pullInStartUs) || (endTimeUs <= startTimeUs && pullInEndUs <= pullInStartUs)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DreamyShaderProgram(
            context, useHdr, intensity, glowSpread, warmth,
            startTimeUs, endTimeUs, previewState, effectMode, softFocus, colorGrading,
            pullInStartUs, pullInEndUs, pullInFadeIn
        )
}

@UnstableApi
class DreamyShaderProgram(
    context: Context,
    useHdr: Boolean,
    intensity: Float,
    glowSpread: Float,
    warmth: Float,
    private val startTimeUs: Long,
    private val endTimeUs: Long,
    private val previewState: DreamyPreviewState? = null,
    private val effectMode: Float = 0f,
    private val softFocus: Float = 0.35f,
    private val colorGrading: ColorGrading = ColorGrading(),
    private val pullInStartUs: Long = -1L,
    private val pullInEndUs: Long = -1L,
    private val pullInFadeIn: Boolean = false
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram = GlProgram(
        context,
        "shaders/vertex_shader.glsl",
        "shaders/dreamy_effect.glsl"
    )
    private val effectIntensity = intensity.coerceIn(0f, 1f)
    private val effectSpread = glowSpread.coerceIn(0f, 1f)
    private val effectWarmth = warmth.coerceIn(0f, 1f)

    init {
        glProgram.setBufferAttribute(
            "aPosition",
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            2
        )
        glProgram.setBufferAttribute(
            "aTexSamplingCoord",
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            2
        )
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth.coerceAtLeast(1), inputHeight.coerceAtLeast(1))

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val liveIntensity: Float
            val liveSpread: Float
            val liveWarmth: Float
            val liveMode: Float
            val liveSoftFocus: Float
            val active: Boolean
            val grade: ColorGrading
            val pullZoom: Float
            val pullBlur: Float
            val pullFade: Float
            if (previewState != null) {
                liveIntensity = previewState.intensity.coerceIn(0f, 1f)
                liveSpread = previewState.glowSpread.coerceIn(0f, 1f)
                liveWarmth = previewState.warmth.coerceIn(0f, 1f)
                liveMode = previewState.effectMode.coerceIn(0f, 2f)
                liveSoftFocus = previewState.softFocus.coerceIn(0f, 1f)
                grade = previewState.colorGrading
                pullZoom = previewState.pullInZoom.coerceIn(1f, 2f)
                pullBlur = previewState.pullInBlur.coerceIn(0f, 1f)
                pullFade = previewState.pullInFade.coerceIn(0f, 1f)
                active = liveIntensity > 0.001f
            } else {
                liveIntensity = effectIntensity
                liveSpread = effectSpread
                liveWarmth = effectWarmth
                liveMode = effectMode.coerceIn(0f, 0f + 2f)
                liveSoftFocus = softFocus.coerceIn(0f, 1f)
                grade = colorGrading
                if (pullInEndUs > pullInStartUs && presentationTimeUs >= pullInStartUs && presentationTimeUs < pullInEndUs) {
                    val progress = ((presentationTimeUs - pullInStartUs).toFloat() / (pullInEndUs - pullInStartUs).toFloat()).coerceIn(0f, 1f)
                    val eased = progress * progress * (3f - 2f * progress)
                    pullZoom = if (pullInFadeIn) 1f else 1f + eased
                    pullBlur = 0f
                    pullFade = if (pullInFadeIn) 1f - eased else eased
                } else {
                    pullZoom = 1f
                    pullBlur = 0f
                    pullFade = 0f
                }
                active = presentationTimeUs >= startTimeUs && presentationTimeUs < endTimeUs
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            glProgram.setFloatUniform("uIntensity", if (active) liveIntensity else 0f)
            glProgram.setFloatUniform("uPullInZoom", pullZoom)
            glProgram.setFloatUniform("uPullInBlur", pullBlur)
            glProgram.setFloatUniform("uPullInFade", pullFade)
            glProgram.setFloatUniform("uHasColorGrade", if (grade.isDefault) 0f else 1f)
            glProgram.setFloatUniform("uBlurRadius", 0.001f + liveSpread * 0.014f)
            glProgram.setFloatUniform("uWarmth", liveWarmth)
            glProgram.setFloatUniform("uEffectMode", liveMode)
            glProgram.setFloatUniform("uSoftFocus", liveSoftFocus)
            glProgram.setFloatUniform("uExposure", grade.exposure)
            glProgram.setFloatUniform("uContrast", grade.contrast)
            glProgram.setFloatUniform("uHighlights", grade.highlights)
            glProgram.setFloatUniform("uShadows", grade.shadows)
            glProgram.setFloatUniform("uWhites", grade.whites)
            glProgram.setFloatUniform("uBlacks", grade.blacks)
            glProgram.setFloatUniform("uTemperature", grade.temperature)
            glProgram.setFloatUniform("uTint", grade.tint)
            glProgram.setFloatUniform("uVibrance", grade.vibrance)
            glProgram.setFloatUniform("uSaturation", grade.saturation)
            glProgram.setFloatUniform("uTexture", grade.texture)
            glProgram.setFloatUniform("uClarity", grade.clarity)
            glProgram.setFloatUniform("uDehaze", grade.dehaze)
            glProgram.setFloatUniform("uVignette", grade.vignette)
            glProgram.setFloatUniform("uGrain", grade.grain)
            glProgram.setFloatUniform("uSharpening", grade.sharpening)
            glProgram.setFloatUniform("uNoiseReduction", grade.noiseReduction)
            glProgram.setFloatUniform("uRedHue", grade.redHue)
            glProgram.setFloatUniform("uRedSaturation", grade.redSaturation)
            glProgram.setFloatUniform("uRedLuminance", grade.redLuminance)
            glProgram.setFloatUniform("uOrangeHue", grade.orangeHue)
            glProgram.setFloatUniform("uOrangeSaturation", grade.orangeSaturation)
            glProgram.setFloatUniform("uOrangeLuminance", grade.orangeLuminance)
            glProgram.setFloatUniform("uYellowHue", grade.yellowHue)
            glProgram.setFloatUniform("uYellowSaturation", grade.yellowSaturation)
            glProgram.setFloatUniform("uYellowLuminance", grade.yellowLuminance)
            glProgram.setFloatUniform("uGreenHue", grade.greenHue)
            glProgram.setFloatUniform("uGreenSaturation", grade.greenSaturation)
            glProgram.setFloatUniform("uGreenLuminance", grade.greenLuminance)
            glProgram.setFloatUniform("uAquaHue", grade.aquaHue)
            glProgram.setFloatUniform("uAquaSaturation", grade.aquaSaturation)
            glProgram.setFloatUniform("uAquaLuminance", grade.aquaLuminance)
            glProgram.setFloatUniform("uBlueHue", grade.blueHue)
            glProgram.setFloatUniform("uBlueSaturation", grade.blueSaturation)
            glProgram.setFloatUniform("uBlueLuminance", grade.blueLuminance)
            glProgram.setFloatUniform("uPurpleHue", grade.purpleHue)
            glProgram.setFloatUniform("uPurpleSaturation", grade.purpleSaturation)
            glProgram.setFloatUniform("uPurpleLuminance", grade.purpleLuminance)
            glProgram.setFloatUniform("uMagentaHue", grade.magentaHue)
            glProgram.setFloatUniform("uMagentaSaturation", grade.magentaSaturation)
            glProgram.setFloatUniform("uMagentaLuminance", grade.magentaLuminance)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // Hindari glGetError pada setiap frame: pemeriksaan ini dapat memaksa sinkronisasi CPU-GPU.
        } catch (e: VideoFrameProcessingException) {
            throw e
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            super.release()
            glProgram.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
