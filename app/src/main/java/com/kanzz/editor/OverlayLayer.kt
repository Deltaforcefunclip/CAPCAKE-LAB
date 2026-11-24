package com.kanzz.editor

import java.util.UUID

/**
 * Media overlay yang ditempatkan di atas video utama.
 *
 * startTimeMs/durationMs berada pada timeline project, sedangkan
 * sourceStartMs/sourceDurationMs menunjuk rentang sumber untuk overlay video.
 * IMAGE memakai sourceStartMs = 0 dan durasi tampil diatur oleh durationMs.
 */
data class OverlayClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: String,
    val mediaType: Type,
    var startTimeMs: Long,
    var durationMs: Long = 2000L,
    var sourceStartMs: Long = 0L,
    var sourceDurationMs: Long = durationMs,
    var trackIndex: Int = 0,
    var positionX: Float = 0f,
    var positionY: Float = 0f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f
) {
    enum class Type { VIDEO, IMAGE }

    val endTimeMs: Long get() = startTimeMs + durationMs
    val sourceEndMs: Long get() = sourceStartMs + sourceDurationMs
}

/**
 * Pengelola track overlay terpisah dari VideoClipManager.
 * Klip pada track yang sama tidak boleh saling bertumpuk.
 */
class OverlayClipManager {
    val clips = mutableListOf<OverlayClip>()

    fun find(id: String?): OverlayClip? = clips.firstOrNull { it.id == id }

    fun firstAvailableLane(startTimeMs: Long, durationMs: Long, excludeId: String? = null): Int {
        val endTimeMs = startTimeMs + durationMs
        var lane = 0
        while (clips.any { existing ->
                existing.id != excludeId &&
                    existing.trackIndex == lane &&
                    startTimeMs < existing.endTimeMs &&
                    endTimeMs > existing.startTimeMs
            }) lane++
        return lane
    }

    fun add(clip: OverlayClip) {
        clip.startTimeMs = clip.startTimeMs.coerceAtLeast(0L)
        clip.durationMs = clip.durationMs.coerceAtLeast(1L)
        clip.sourceStartMs = clip.sourceStartMs.coerceAtLeast(0L)
        clip.sourceDurationMs = clip.sourceDurationMs.coerceAtLeast(1L)
        clip.trackIndex = firstAvailableLane(clip.startTimeMs, clip.durationMs)
        clip.opacity = clip.opacity.coerceIn(0f, 1f)
        clip.scale = clip.scale.coerceIn(0.05f, 10f)
        clips.add(clip)
        sort()
    }

    fun canMoveToLane(clip: OverlayClip, lane: Int): Boolean =
        lane >= 0 && clips.none { other ->
            other.id != clip.id && other.trackIndex == lane &&
                clip.startTimeMs < other.endTimeMs &&
                clip.endTimeMs > other.startTimeMs
        }

    fun moveClipToLane(id: String, lane: Int): Boolean {
        val clip = find(id) ?: return false
        if (!canMoveToLane(clip, lane)) return false
        clip.trackIndex = lane
        sort()
        return true
    }

    /** Memindahkan waktu dan lane sekaligus; perubahan ditolak jika terjadi tabrakan. */
    fun moveClip(id: String, newStartTimeMs: Long, newLane: Int): Boolean {
        val clip = find(id) ?: return false
        if (newStartTimeMs < 0L || newLane < 0) return false
        val oldStart = clip.startTimeMs
        val oldLane = clip.trackIndex
        clip.startTimeMs = newStartTimeMs
        if (!canMoveToLane(clip, newLane)) {
            clip.startTimeMs = oldStart
            clip.trackIndex = oldLane
            return false
        }
        clip.trackIndex = newLane
        sort()
        return true
    }

    fun moveClipToNewBottomLane(id: String): Boolean {
        val clip = find(id) ?: return false
        clip.trackIndex = (clips.maxOfOrNull { it.trackIndex } ?: -1) + 1
        compactLanes()
        return true
    }

    fun remove(id: String?): Boolean {
        val removed = clips.removeAll { it.id == id }
        if (removed) compactLanes()
        return removed
    }

    fun compactLanes() {
        val lanes = clips.map { it.trackIndex }.distinct().sorted()
        val remap = lanes.mapIndexed { index, old -> old to index }.toMap()
        clips.forEach { it.trackIndex = remap[it.trackIndex] ?: 0 }
        sort()
    }

    fun split(id: String?, positionMs: Long): OverlayClip? {
        val clip = find(id) ?: return null
        if (positionMs <= clip.startTimeMs || positionMs >= clip.endTimeMs) return null
        val firstDuration = positionMs - clip.startTimeMs
        val secondDuration = clip.endTimeMs - positionMs
        val sourceSplit = if (clip.mediaType == OverlayClip.Type.VIDEO) {
            (clip.sourceDurationMs * (firstDuration.toDouble() / clip.durationMs)).toLong()
        } else clip.sourceDurationMs
        val second = clip.copy(
            id = UUID.randomUUID().toString(),
            startTimeMs = positionMs,
            durationMs = secondDuration,
            sourceStartMs = if (clip.mediaType == OverlayClip.Type.VIDEO) clip.sourceStartMs + sourceSplit else 0L,
            sourceDurationMs = if (clip.mediaType == OverlayClip.Type.VIDEO) {
                (clip.sourceDurationMs - sourceSplit).coerceAtLeast(1L)
            } else clip.sourceDurationMs
        )
        clip.durationMs = firstDuration
        if (clip.mediaType == OverlayClip.Type.VIDEO) clip.sourceDurationMs = sourceSplit.coerceAtLeast(1L)
        add(second)
        return second
    }

    fun trim(id: String, newStartTimeMs: Long, newDurationMs: Long): Boolean {
        val clip = find(id) ?: return false
        if (newStartTimeMs < 0L || newDurationMs < 1L) return false
        val oldStart = clip.startTimeMs
        val oldDuration = clip.durationMs
        clip.startTimeMs = newStartTimeMs
        clip.durationMs = newDurationMs
        if (!canMoveToLane(clip, clip.trackIndex)) {
            clip.startTimeMs = oldStart
            clip.durationMs = oldDuration
            return false
        }
        sort()
        return true
    }

    private fun sort() {
        clips.sortWith(compareBy<OverlayClip> { it.trackIndex }.thenBy { it.startTimeMs })
    }
}
