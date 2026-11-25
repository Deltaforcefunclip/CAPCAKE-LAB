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
        // Overlay video belum memiliki kontrol speed. Karena itu durasi sumber
        // harus sama persis dengan durasi timeline agar sequence export tidak drift.
        clip.sourceDurationMs = if (clip.mediaType == OverlayClip.Type.VIDEO) {
            clip.durationMs
        } else {
            clip.sourceDurationMs.coerceAtLeast(1L)
        }
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

    /** Validasi perpindahan tanpa mengubah state manager. */
    fun canMove(id: String, newStartTimeMs: Long, newLane: Int): Boolean {
        val clip = find(id) ?: return false
        if (newStartTimeMs < 0L || newLane < 0) return false
        val newEndTimeMs = newStartTimeMs + clip.durationMs
        return clips.none { other ->
            other.id != clip.id &&
                other.trackIndex == newLane &&
                newStartTimeMs < other.endTimeMs &&
                newEndTimeMs > other.startTimeMs
        }
    }

    /** Memindahkan waktu dan lane sekaligus; perubahan ditolak jika terjadi tabrakan. */
    fun moveClip(id: String, newStartTimeMs: Long, newLane: Int): Boolean {
        val clip = find(id) ?: return false
        if (!canMove(id, newStartTimeMs, newLane)) return false
        clip.startTimeMs = newStartTimeMs
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
        // Tidak ada speed overlay: pembagian source range harus identik dengan
        // pembagian timeline. Pembulatan rasio dapat menimbulkan drift antar-klip.
        val sourceSplit = if (clip.mediaType == OverlayClip.Type.VIDEO) {
            firstDuration
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

    /** Validasi trim tanpa memutasi klip; berguna sebelum membuat undo snapshot. */
    fun canTrim(id: String, newStartTimeMs: Long, newDurationMs: Long): Boolean {
        val clip = find(id) ?: return false
        if (newStartTimeMs < clip.startTimeMs || newDurationMs < 1L) return false
        val newEnd = newStartTimeMs + newDurationMs
        if (newEnd > oldEnd) return false
        return clips.none { other ->
            other.id != clip.id &&
                other.trackIndex == clip.trackIndex &&
                newStartTimeMs < other.endTimeMs &&
                newEnd > other.startTimeMs
        }
    }

    fun trim(id: String, newStartTimeMs: Long, newDurationMs: Long): Boolean {
        val clip = find(id) ?: return false
        if (!canTrim(id, newStartTimeMs, newDurationMs)) return false
        val oldStart = clip.startTimeMs
        val oldSourceStart = clip.sourceStartMs
        val oldSourceDuration = clip.sourceDurationMs
        val oldEnd = clip.endTimeMs
        val newEnd = newStartTimeMs + newDurationMs

        if (clip.mediaType == OverlayClip.Type.VIDEO) {
            // 1x playback: memotong 300 ms di timeline berarti memotong 300 ms
            // dari sumber. Ini menjaga durasi sequence export tetap sejajar.
            clip.sourceStartMs = oldSourceStart + (newStartTimeMs - oldStart)
            clip.sourceDurationMs = newDurationMs
        }
        clip.startTimeMs = newStartTimeMs
        clip.durationMs = newDurationMs
        sort()
        return true
    }

    private fun sort() {
        clips.sortWith(compareBy<OverlayClip> { it.trackIndex }.thenBy { it.startTimeMs })
    }
}
