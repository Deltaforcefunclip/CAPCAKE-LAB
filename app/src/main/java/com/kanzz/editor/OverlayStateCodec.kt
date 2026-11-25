package com.kanzz.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, isolated persistence for media overlays.
 * Restore is atomic: malformed JSON never clears the current manager.
 */
object OverlayStateCodec {
    private const val VERSION = 1

    fun encode(manager: OverlayClipManager): String {
        val clips = JSONArray()
        manager.clips.forEach { clip ->
            clips.put(JSONObject().apply {
                put("id", clip.id)
                put("sourceUri", clip.sourceUri)
                put("mediaType", clip.mediaType.name)
                put("startTimeMs", clip.startTimeMs)
                put("durationMs", clip.durationMs)
                put("sourceStartMs", clip.sourceStartMs)
                put("sourceDurationMs", clip.sourceDurationMs)
                put("trackIndex", clip.trackIndex)
                put("positionX", clip.positionX.toDouble())
                put("positionY", clip.positionY.toDouble())
                put("scale", clip.scale.toDouble())
                put("rotation", clip.rotation.toDouble())
                put("opacity", clip.opacity.toDouble())
                put("volume", clip.volume.toDouble())
                put("cropLeft", clip.cropLeft.toDouble())
                put("cropTop", clip.cropTop.toDouble())
                put("cropRight", clip.cropRight.toDouble())
                put("cropBottom", clip.cropBottom.toDouble())
            })
        }
        return JSONObject()
            .put("version", VERSION)
            .put("clips", clips)
            .toString()
    }

    fun restore(json: String?, manager: OverlayClipManager): Boolean {
        if (json.isNullOrBlank()) {
            manager.clips.clear()
            return true
        }

        val restored = OverlayClipManager()
        try {
            val payload = JSONObject(json)
            // Payload versi lebih baru mungkin memiliki field atau semantik yang
            // belum dipahami aplikasi ini. Tolak secara atomik, jangan menimpa
            // project dengan hasil restore parsial.
            val payloadVersion = if (payload.has("version")) payload.getInt("version") else 1
            require(payloadVersion in 1..VERSION) { "Unsupported overlay state version" }
            val array = payload.getJSONArray("clips")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val type = OverlayClip.Type.valueOf(item.getString("mediaType"))
                val clip = OverlayClip(
                    id = item.getString("id"),
                    sourceUri = item.getString("sourceUri"),
                    mediaType = type,
                    startTimeMs = item.getLong("startTimeMs"),
                    durationMs = item.getLong("durationMs"),
                    sourceStartMs = item.optLong("sourceStartMs", 0L),
                    sourceDurationMs = item.optLong(
                        "sourceDurationMs",
                        item.optLong("durationMs", 1L)
                    ),
                    trackIndex = item.optInt("trackIndex", 0),
                    positionX = item.optDouble("positionX", 0.0).toFloat(),
                    positionY = item.optDouble("positionY", 0.0).toFloat(),
                    scale = item.optDouble("scale", 1.0).toFloat(),
                    rotation = item.optDouble("rotation", 0.0).toFloat(),
                    opacity = item.optDouble("opacity", 1.0).toFloat(),
                    volume = item.optDouble("volume", 1.0).toFloat(),
                    cropLeft = item.optDouble("cropLeft", 0.0).toFloat(),
                    cropTop = item.optDouble("cropTop", 0.0).toFloat(),
                    cropRight = item.optDouble("cropRight", 0.0).toFloat(),
                    cropBottom = item.optDouble("cropBottom", 0.0).toFloat()
                )
                require(clip.startTimeMs >= 0L && clip.durationMs > 0L)
                require(clip.sourceStartMs >= 0L && clip.sourceDurationMs > 0L)
                // Overlay video diputar 1x. Normalisasi payload lama yang durasi
                // sumbernya berbeda agar posisi klip berikutnya tidak bergeser saat export.
                if (clip.mediaType == OverlayClip.Type.VIDEO) {
                    clip.sourceDurationMs = clip.durationMs
                }
                require(clip.trackIndex >= 0)
                clip.scale = clip.scale.coerceIn(0.05f, 10f)
                clip.opacity = clip.opacity.coerceIn(0f, 1f)
                clip.volume = clip.volume.coerceIn(0f, 2f)
                clip.cropLeft = clip.cropLeft.coerceIn(0f, 0.9f)
                clip.cropTop = clip.cropTop.coerceIn(0f, 0.9f)
                clip.cropRight = clip.cropRight.coerceIn(0f, 0.9f)
                clip.cropBottom = clip.cropBottom.coerceIn(0f, 0.9f)
                clip.volume = clip.volume.coerceIn(0f, 2f)
                require(clip.positionX.isFinite() && clip.positionY.isFinite())
                require(clip.rotation.isFinite() && clip.scale.isFinite() && clip.opacity.isFinite() && clip.volume.isFinite() && clip.cropLeft.isFinite() && clip.cropTop.isFinite() && clip.cropRight.isFinite() && clip.cropBottom.isFinite())
                require(restored.find(clip.id) == null) { "Duplicate overlay clip id" }
                restored.clips.add(clip)
            }

            // Validate track collisions instead of silently moving saved clips.
            restored.clips.groupBy { it.trackIndex }.values.forEach { lane ->
                val sorted = lane.sortedBy { it.startTimeMs }
                for (index in 1 until sorted.size) {
                    require(sorted[index].startTimeMs >= sorted[index - 1].endTimeMs) {
                        "Overlapping clips in the same overlay lane"
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }

        manager.clips.clear()
        manager.clips.addAll(restored.clips.sortedWith(
            compareBy<OverlayClip> { it.trackIndex }.thenBy { it.startTimeMs }
        ))
        return true
    }
}
