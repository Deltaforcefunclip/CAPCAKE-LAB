package com.kanzz.editor

import org.json.JSONArray
import org.json.JSONObject

/** Versioned persistence for editable video and audio timeline data. */
object TimelineStateCodec {
    private const val VERSION = 1

    fun encodeVideo(manager: VideoClipManager): String {
        val root = JSONObject().put("version", VERSION)
        val clips = JSONArray()
        manager.clips.forEach { clip ->
            clips.put(JSONObject().apply {
                put("id", clip.id)
                put("sourceUri", clip.sourceUri)
                put("sourceStartMs", clip.sourceStartMs)
                put("durationMs", clip.durationMs)
                put("sourceDurationMs", clip.sourceDurationMs)
                put("volume", clip.volume.toDouble())
                put("speed", clip.speed.toDouble())
                put("opacity", clip.opacity.toDouble())
                put("filterName", clip.filterName)
                put("brightness", clip.brightness.toDouble())
                put("contrast", clip.contrast.toDouble())
                put("saturation", clip.saturation.toDouble())
                put("rotationDegrees", clip.rotationDegrees.toDouble())
                put("transformScale", clip.transformScale.toDouble())
                put("transformX", clip.transformX.toDouble())
                put("transformY", clip.transformY.toDouble())
                put("motionBlurIntensity", clip.motionBlurIntensity.toDouble())
                put("motionBlurBlend", clip.motionBlurBlend.toDouble())
                put("motionBlurDirection", clip.motionBlurDirection)
                put("motionBlurTimes", clip.motionBlurTimes)
                put("colorGrading", JSONObject().apply {
                    val grade = clip.colorGrading
                    put("exposure", grade.exposure.toDouble()); put("contrast", grade.contrast.toDouble())
                    put("highlights", grade.highlights.toDouble()); put("shadows", grade.shadows.toDouble())
                    put("whites", grade.whites.toDouble()); put("blacks", grade.blacks.toDouble())
                    put("temperature", grade.temperature.toDouble()); put("tint", grade.tint.toDouble())
                    put("vibrance", grade.vibrance.toDouble()); put("saturation", grade.saturation.toDouble())
                    put("texture", grade.texture.toDouble()); put("clarity", grade.clarity.toDouble())
                    put("dehaze", grade.dehaze.toDouble()); put("vignette", grade.vignette.toDouble())
                    put("grain", grade.grain.toDouble()); put("sharpening", grade.sharpening.toDouble())
                    put("noiseReduction", grade.noiseReduction.toDouble())
                    put("redHue", grade.redHue.toDouble())
                    put("redSaturation", grade.redSaturation.toDouble())
                    put("redLuminance", grade.redLuminance.toDouble())
                    put("orangeHue", grade.orangeHue.toDouble())
                    put("orangeSaturation", grade.orangeSaturation.toDouble())
                    put("orangeLuminance", grade.orangeLuminance.toDouble())
                    put("yellowHue", grade.yellowHue.toDouble())
                    put("yellowSaturation", grade.yellowSaturation.toDouble())
                    put("yellowLuminance", grade.yellowLuminance.toDouble())
                    put("greenHue", grade.greenHue.toDouble())
                    put("greenSaturation", grade.greenSaturation.toDouble())
                    put("greenLuminance", grade.greenLuminance.toDouble())
                    put("aquaHue", grade.aquaHue.toDouble())
                    put("aquaSaturation", grade.aquaSaturation.toDouble())
                    put("aquaLuminance", grade.aquaLuminance.toDouble())
                    put("blueHue", grade.blueHue.toDouble())
                    put("blueSaturation", grade.blueSaturation.toDouble())
                    put("blueLuminance", grade.blueLuminance.toDouble())
                    put("purpleHue", grade.purpleHue.toDouble())
                    put("purpleSaturation", grade.purpleSaturation.toDouble())
                    put("purpleLuminance", grade.purpleLuminance.toDouble())
                    put("magentaHue", grade.magentaHue.toDouble())
                    put("magentaSaturation", grade.magentaSaturation.toDouble())
                    put("magentaLuminance", grade.magentaLuminance.toDouble())
                })
            })
        }
        val transitions = JSONArray()
        manager.transitions.forEach { transition ->
            transitions.put(JSONObject().apply {
                put("id", transition.id)
                put("leftClipId", transition.leftClipId)
                put("rightClipId", transition.rightClipId)
                put("type", transition.type.name)
                put("durationMs", transition.durationMs)
            })
        }
        root.put("clips", clips)
        root.put("transitions", transitions)
        return root.toString()
    }

    fun restoreVideo(json: String?, manager: VideoClipManager) {
        manager.clips.clear()
        manager.transitions.clear()
        if (json.isNullOrBlank()) return
        try {
            val root = JSONObject(json)
            val clips = root.optJSONArray("clips") ?: JSONArray()
            for (i in 0 until clips.length()) {
                val item = clips.getJSONObject(i)
                manager.clips.add(VideoClip(
                    id = item.optString("id"),
                    sourceUri = item.getString("sourceUri"),
                    sourceStartMs = item.optLong("sourceStartMs"),
                    durationMs = item.optLong("durationMs"),
                    sourceDurationMs = item.optLong("sourceDurationMs", item.optLong("durationMs")),
                    volume = item.optDouble("volume", 1.0).toFloat(),
                    speed = item.optDouble("speed", 1.0).toFloat(),
                    opacity = item.optDouble("opacity", 1.0).toFloat(),
                    filterName = item.optString("filterName", "None"),
                    brightness = item.optDouble("brightness", 0.0).toFloat(),
                    contrast = item.optDouble("contrast", 1.0).toFloat(),
                    saturation = item.optDouble("saturation", 1.0).toFloat(),
                    rotationDegrees = item.optDouble("rotationDegrees", 0.0).toFloat(),
                    transformScale = item.optDouble("transformScale", 1.0).toFloat().coerceIn(0.35f, 5f),
                    transformX = item.optDouble("transformX", 0.0).toFloat(),
                    transformY = item.optDouble("transformY", 0.0).toFloat(),
                    motionBlurIntensity = item.optDouble("motionBlurIntensity", 0.0).toFloat().coerceIn(0f, 1f),
                    motionBlurBlend = item.optDouble("motionBlurBlend", 0.65).toFloat().coerceIn(0f, 1f),
                    motionBlurDirection = item.optInt("motionBlurDirection", 0).coerceIn(0, 2),
                    motionBlurTimes = item.optInt("motionBlurTimes", 4).let { if (it == 1 || it == 2 || it == 4 || it == 6) it else 4 },
                    colorGrading = item.optJSONObject("colorGrading")?.let { grade ->
                        ColorGrading(
                            exposure = grade.optDouble("exposure", 0.0).toFloat(),
                            contrast = grade.optDouble("contrast", 0.0).toFloat(),
                            highlights = grade.optDouble("highlights", 0.0).toFloat(),
                            shadows = grade.optDouble("shadows", 0.0).toFloat(),
                            whites = grade.optDouble("whites", 0.0).toFloat(),
                            blacks = grade.optDouble("blacks", 0.0).toFloat(),
                            temperature = grade.optDouble("temperature", 0.0).toFloat(),
                            tint = grade.optDouble("tint", 0.0).toFloat(),
                            vibrance = grade.optDouble("vibrance", 0.0).toFloat(),
                            saturation = grade.optDouble("saturation", 0.0).toFloat(),
                            texture = grade.optDouble("texture", 0.0).toFloat(),
                            clarity = grade.optDouble("clarity", 0.0).toFloat(),
                            dehaze = grade.optDouble("dehaze", 0.0).toFloat(),
                            vignette = grade.optDouble("vignette", 0.0).toFloat(),
                            grain = grade.optDouble("grain", 0.0).toFloat(),
                            sharpening = grade.optDouble("sharpening", 0.0).toFloat(),
                            noiseReduction = grade.optDouble("noiseReduction", 0.0).toFloat(),
                            redHue = grade.optDouble("redHue", 0.0).toFloat(),
                            redSaturation = grade.optDouble("redSaturation", 0.0).toFloat(),
                            redLuminance = grade.optDouble("redLuminance", 0.0).toFloat(),
                            orangeHue = grade.optDouble("orangeHue", 0.0).toFloat(),
                            orangeSaturation = grade.optDouble("orangeSaturation", 0.0).toFloat(),
                            orangeLuminance = grade.optDouble("orangeLuminance", 0.0).toFloat(),
                            yellowHue = grade.optDouble("yellowHue", 0.0).toFloat(),
                            yellowSaturation = grade.optDouble("yellowSaturation", 0.0).toFloat(),
                            yellowLuminance = grade.optDouble("yellowLuminance", 0.0).toFloat(),
                            greenHue = grade.optDouble("greenHue", 0.0).toFloat(),
                            greenSaturation = grade.optDouble("greenSaturation", 0.0).toFloat(),
                            greenLuminance = grade.optDouble("greenLuminance", 0.0).toFloat(),
                            aquaHue = grade.optDouble("aquaHue", 0.0).toFloat(),
                            aquaSaturation = grade.optDouble("aquaSaturation", 0.0).toFloat(),
                            aquaLuminance = grade.optDouble("aquaLuminance", 0.0).toFloat(),
                            blueHue = grade.optDouble("blueHue", 0.0).toFloat(),
                            blueSaturation = grade.optDouble("blueSaturation", 0.0).toFloat(),
                            blueLuminance = grade.optDouble("blueLuminance", 0.0).toFloat(),
                            purpleHue = grade.optDouble("purpleHue", 0.0).toFloat(),
                            purpleSaturation = grade.optDouble("purpleSaturation", 0.0).toFloat(),
                            purpleLuminance = grade.optDouble("purpleLuminance", 0.0).toFloat(),
                            magentaHue = grade.optDouble("magentaHue", 0.0).toFloat(),
                            magentaSaturation = grade.optDouble("magentaSaturation", 0.0).toFloat(),
                            magentaLuminance = grade.optDouble("magentaLuminance", 0.0).toFloat()
                        )
                    } ?: ColorGrading()
                ))
            }
            val transitions = root.optJSONArray("transitions") ?: JSONArray()
            for (i in 0 until transitions.length()) {
                val item = transitions.getJSONObject(i)
                val type = runCatching {
                    VideoTransition.Type.valueOf(item.optString("type", "NONE"))
                }.getOrDefault(VideoTransition.Type.NONE)
                manager.transitions.add(VideoTransition(
                    id = item.optString("id"),
                    leftClipId = item.getString("leftClipId"),
                    rightClipId = item.getString("rightClipId"),
                    type = type,
                    durationMs = item.optLong("durationMs", 500L)
                ))
            }
            manager.recalculateTimeline()
        } catch (_: Exception) {
            manager.clips.clear()
            manager.transitions.clear()
        }
    }

    fun encodeAudio(manager: AudioClipManager): String {
        val root = JSONObject().put("version", VERSION)
        val clips = JSONArray()
        manager.clips.forEach { clip ->
            val laneIndex = manager.lanes.indexOfFirst { lane -> lane.clips.any { it.id == clip.id } }
            clips.put(JSONObject().apply {
                put("laneIndex", laneIndex)
                put("id", clip.id)
                put("startTimeMs", clip.startTimeMs)
                put("durationMs", clip.durationMs)
                put("filePath", clip.filePath)
                put("sourceStartMs", clip.sourceStartMs)
                put("sourceDurationMs", clip.sourceDurationMs)
                put("name", clip.name)
                put("volume", clip.volume.toDouble())
                put("speed", clip.speed.toDouble())
            })
        }
        root.put("clips", clips)
        return root.toString()
    }

    fun restoreAudio(json: String?, manager: AudioClipManager) {
        manager.clear()
        if (json.isNullOrBlank()) return
        try {
            val clips = JSONObject(json).optJSONArray("clips") ?: JSONArray()
            for (i in 0 until clips.length()) {
                val item = clips.getJSONObject(i)
                val clip = AudioClip(
                    id = item.optString("id"),
                    startTimeMs = item.optLong("startTimeMs"),
                    durationMs = item.optLong("durationMs"),
                    filePath = item.getString("filePath"),
                    sourceStartMs = item.optLong("sourceStartMs"),
                    name = item.optString("name", "Audio"),
                    sourceDurationMs = item.optLong("sourceDurationMs", item.optLong("durationMs")),
                    volume = item.optDouble("volume", 1.0).toFloat(),
                    speed = item.optDouble("speed", 1.0).toFloat()
                )
                val laneIndex = item.optInt("laneIndex", -1)
                if (laneIndex >= 0) {
                    while (manager.lanes.size <= laneIndex) manager.lanes.add(AudioLane())
                    val lane = manager.lanes[laneIndex]
                    if (lane.clips.none { existing ->
                            clip.startTimeMs < existing.endTimeMs &&
                                clip.endTimeMs > existing.startTimeMs
                        }) {
                        lane.clips.add(clip)
                        manager.clips.add(clip)
                    } else {
                        manager.addClip(clip)
                    }
                } else {
                    manager.addClip(clip)
                }
            }
        } catch (_: Exception) {
            manager.clear()
        }
    }
}
