package com.kanzz.editor

import java.util.UUID

data class AudioClip(
    val id: String = UUID.randomUUID().toString(),
    var startTimeMs: Long = 0L,
    var durationMs: Long = 0L,
    val filePath: String,
    var sourceStartMs: Long = 0L,
    var name: String = "Audio",
    var volume: Float = 1f,
    var speed: Float = 1f,
    var sourceDurationMs: Long = durationMs
) {
    val endTimeMs: Long
        get() = startTimeMs + durationMs
}

data class AudioLane(
    val id: String = UUID.randomUUID().toString(),
    val clips: MutableList<AudioClip> = mutableListOf()
)

class AudioClipManager {
    val clips = mutableListOf<AudioClip>()
    val lanes = mutableListOf<AudioLane>()

    fun addClip(clip: AudioClip): AudioLane {
        val lane = lanes.firstOrNull { candidate ->
            candidate.clips.none { existing ->
                clip.startTimeMs < existing.endTimeMs &&
                    clip.endTimeMs > existing.startTimeMs
            }
        } ?: AudioLane().also { lanes.add(it) }

        lane.clips.add(clip)
        clips.add(clip)
        return lane
    }

    fun moveClipToLane(clipId: String, targetLaneId: String): Boolean {
        val clip = clips.firstOrNull { it.id == clipId } ?: return false
        val target = lanes.firstOrNull { it.id == targetLaneId } ?: return false
        if (target.clips.any { other ->
                other.id != clip.id &&
                    clip.startTimeMs < other.endTimeMs &&
                    clip.endTimeMs > other.startTimeMs
            }) return false
        val source = lanes.firstOrNull { lane -> lane.clips.any { it.id == clipId } }
        if (source?.id == target.id) return true
        source?.clips?.removeAll { it.id == clipId }
        if (target.clips.none { it.id == clipId }) target.clips.add(clip)
        // Layer kosong otomatis dibuang agar tidak meninggalkan ruang kosong.
        lanes.removeAll { it.clips.isEmpty() }
        return true
    }

    fun createLaneAndMoveClip(clipId: String): Boolean {
        val clip = clips.firstOrNull { it.id == clipId } ?: return false
        val source = lanes.firstOrNull { lane -> lane.clips.any { it.id == clipId } }
        val newLane = AudioLane()
        newLane.clips.add(clip)
        lanes.add(newLane)
        source?.clips?.removeAll { it.id == clipId }
        lanes.removeAll { it.clips.isEmpty() }
        return true
    }

    fun removeClip(clipId: String): Boolean {
        val clip = clips.firstOrNull { it.id == clipId } ?: return false
        clips.remove(clip)
        lanes.forEach { it.clips.removeAll { item -> item.id == clipId } }
        lanes.removeAll { it.clips.isEmpty() }
        return true
    }

    fun clear() {
        clips.clear()
        lanes.clear()
    }

    fun splitAudio(playheadMs: Long, selectedClipId: String? = null): AudioClip? {
        val clip = clips.firstOrNull {
            (selectedClipId == null || it.id == selectedClipId) &&
                playheadMs > it.startTimeMs &&
                playheadMs < it.endTimeMs
        } ?: return null

        val firstDuration = playheadMs - clip.startTimeMs
        val secondDuration = clip.durationMs - firstDuration

        val secondClip = AudioClip(
            startTimeMs = playheadMs,
            durationMs = secondDuration,
            filePath = clip.filePath,
            sourceStartMs = clip.sourceStartMs + firstDuration,
            name = clip.name,
            volume = clip.volume,
            speed = clip.speed,
            sourceDurationMs = clip.sourceDurationMs
        )

        clip.durationMs = firstDuration
        clips.add(secondClip)
        lanes.firstOrNull { lane -> lane.clips.contains(clip) }
            ?.clips?.add(secondClip)

        return secondClip
    }

    fun trimStart(clipId: String, newStartMs: Long): Boolean {
        val clip = clips.find { it.id == clipId } ?: return false
        if (newStartMs <= clip.startTimeMs || newStartMs >= clip.endTimeMs) return false
        val difference = newStartMs - clip.startTimeMs
        clip.startTimeMs = newStartMs
        clip.durationMs -= difference
        clip.sourceStartMs += difference
        return true
    }

    fun trimEnd(clipId: String, newEndMs: Long): Boolean {
        val clip = clips.find { it.id == clipId } ?: return false
        if (newEndMs <= clip.startTimeMs || newEndMs >= clip.endTimeMs) return false
        clip.durationMs = newEndMs - clip.startTimeMs
        return true
    }
}
