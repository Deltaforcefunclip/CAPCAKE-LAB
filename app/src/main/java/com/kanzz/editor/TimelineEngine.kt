package com.kanzz.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fondasi timeline CAPCAKE yang independen dari View.
 *
 * Semua posisi/durasi disimpan dalam ms. Pixel hanya dipakai oleh SnapEngine
 * untuk menghitung toleransi sentuhan dan oleh viewport untuk windowing.
 */
data class TimelineKeyframe(
    val id: String,
    val timeMs: Long,
    val property: String,
    val value: Float,
    val interpolation: String = "LINEAR"
)

data class EditorClip(
    val id: String,
    val mediaUri: String,
    val trackIndex: Int,
    val startTime: Long,
    val duration: Long,
    val trimStart: Long = 0L,
    val trimEnd: Long = 0L,
    val sourceDuration: Long,
    val zIndex: Int = 0,
    val keyframes: List<TimelineKeyframe> = emptyList()
) {
    val endTime: Long get() = startTime + duration
    val sourceStart: Long get() = trimStart
    val sourceEnd: Long get() = (sourceDuration - trimEnd).coerceAtLeast(trimStart)
}

data class TimelineMarker(val timeMs: Long, val label: String = "")

data class TimelineState(
    val clips: List<EditorClip> = emptyList(),
    val playheadMs: Long = 0L,
    val markers: List<TimelineMarker> = emptyList(),
    val rippleMainTrack: Boolean = true
) {
    fun clip(id: String): EditorClip? = clips.firstOrNull { it.id == id }
    fun durationMs(): Long = clips.maxOfOrNull { it.endTime } ?: 0L
}

data class SnapResult(
    val timeMs: Long,
    val snapped: Boolean,
    val targetTimeMs: Long? = null,
    val guide: String? = null
)

/** Snap berdasarkan toleransi layar, bukan toleransi waktu tetap. */
object TimelineSnapEngine {
    fun snap(
        proposedTimeMs: Long,
        movingClipId: String,
        state: TimelineState,
        pixelsPerMs: Float,
        tolerancePx: Float = 10f,
        extraMarkers: List<TimelineMarker> = emptyList()
    ): SnapResult {
        if (pixelsPerMs <= 0f) return SnapResult(proposedTimeMs, false)

        val toleranceMs = (tolerancePx / pixelsPerMs).toLong().coerceAtLeast(1L)
        val moving = state.clip(movingClipId)
        val movingDuration = moving?.duration ?: 0L
        val candidates = mutableListOf<Pair<Long, String>>()
        candidates += state.playheadMs to "PLAYHEAD"
        (state.markers + extraMarkers).forEach { candidates += it.timeMs to "MARKER" }
        state.clips.asSequence()
            .filter { it.id != movingClipId }
            .forEach { other ->
                candidates += other.startTime to "CLIP_START"
                candidates += other.endTime to "CLIP_END"
            }

        // Snap titik awal klip yang digeser ke target awal/akhir.
        val best = candidates
            .map { (target, kind) -> Triple(abs(target - proposedTimeMs), target, kind) }
            .filter { it.first <= toleranceMs }
            .minByOrNull { it.first }

        return if (best != null) {
            SnapResult(best.second, true, best.second, best.third)
        } else {
            // Jika awal tidak dekat, coba ujung klip agar ujungnya menempel.
            val endBest = candidates
                .map { (target, kind) ->
                    Triple(abs(target - (proposedTimeMs + movingDuration)), target, kind)
                }
                .filter { it.first <= toleranceMs }
                .minByOrNull { it.first }
            if (endBest != null) {
                SnapResult((endBest.second - movingDuration).coerceAtLeast(0L), true, endBest.second, endBest.third)
            } else SnapResult(proposedTimeMs.coerceAtLeast(0L), false)
        }
    }
}

/** Windowing: hanya ambil klip yang berpotongan dengan area waktu layar. */
object TimelineVirtualizer {
    fun visibleClips(state: TimelineState, viewportStartMs: Long, viewportEndMs: Long): List<EditorClip> {
        val start = min(viewportStartMs, viewportEndMs)
        val end = max(viewportStartMs, viewportEndMs)
        return state.clips.asSequence()
            .filter { it.endTime >= start && it.startTime <= end }
            .sortedWith(compareBy<EditorClip> { it.trackIndex }.thenBy { it.startTime }.thenBy { it.zIndex })
            .toList()
    }
}

/** Setiap edit adalah command. State immutable memudahkan Undo/Redo yang konsisten. */
interface TimelineCommand {
    fun apply(state: TimelineState): TimelineState
}

class SplitClipCommand(
    private val clipId: String,
    private val playheadMs: Long,
    private val newClipId: String
) : TimelineCommand {
    override fun apply(state: TimelineState): TimelineState {
        val original = state.clip(clipId) ?: return state
        if (playheadMs <= original.startTime || playheadMs >= original.endTime) return state

        val leftDuration = playheadMs - original.startTime
        val rightDuration = original.duration - leftDuration
        val leftTrimEnd = (original.sourceDuration - original.trimStart - leftDuration).coerceAtLeast(0L)
        val left = original.copy(duration = leftDuration, trimEnd = leftTrimEnd)
        val right = original.copy(
            id = newClipId,
            startTime = playheadMs,
            duration = rightDuration,
            trimStart = original.trimStart + leftDuration
        )
        val result = state.clips.toMutableList()
        val index = result.indexOfFirst { it.id == clipId }
        result[index] = left
        result.add(index + 1, right)
        return state.copy(clips = result)
    }
}

class DeleteClipCommand(private val clipId: String) : TimelineCommand {
    override fun apply(state: TimelineState): TimelineState {
        val removed = state.clip(clipId) ?: return state
        var next = state.copy(clips = state.clips.filterNot { it.id == clipId })
        if (removed.trackIndex == 0 && state.rippleMainTrack) {
            next = next.copy(clips = next.clips.map { clip ->
                if (clip.trackIndex == 0 && clip.startTime >= removed.endTime)
                    clip.copy(startTime = clip.startTime - removed.duration)
                else clip
            })
        }
        return next
    }
}

class MoveClipCommand(
    private val clipId: String,
    private val targetTrackIndex: Int,
    private val targetStartMs: Long
) : TimelineCommand {
    override fun apply(state: TimelineState): TimelineState {
        val moving = state.clip(clipId) ?: return state
        val track = targetTrackIndex.coerceAtLeast(0)
        val start = targetStartMs.coerceAtLeast(0L)
        val moved = moving.copy(trackIndex = track, startTime = start, zIndex = if (track == 0) 0 else moving.zIndex)
        val others = state.clips.filterNot { it.id == clipId }
        if (track != 0 && others.any {
                it.trackIndex == track && start < it.endTime && start + moving.duration > it.startTime
            }) return state
        var next = state.copy(clips = others + moved)
        if (track == 0) next = normalizeMainTrack(next)
        return next
    }
}

class TrimClipCommand(
    private val clipId: String,
    private val leftDeltaMs: Long = 0L,
    private val rightDeltaMs: Long = 0L
) : TimelineCommand {
    override fun apply(state: TimelineState): TimelineState {
        val clip = state.clip(clipId) ?: return state
        val left = leftDeltaMs.coerceIn(-clip.trimStart, clip.duration - 1L)
        val newDurationAfterLeft = clip.duration - left
        val right = rightDeltaMs.coerceIn(1L - newDurationAfterLeft, clip.trimEnd)
        val updated = clip.copy(
            startTime = (clip.startTime + left).coerceAtLeast(0L),
            duration = (newDurationAfterLeft + right).coerceAtLeast(1L),
            trimStart = (clip.trimStart + left).coerceAtLeast(0L),
            trimEnd = (clip.trimEnd - right).coerceAtLeast(0L)
        )
        var next = state.copy(clips = state.clips.map { if (it.id == clipId) updated else it })
        if (clip.trackIndex == 0 && state.rippleMainTrack) next = normalizeMainTrack(next)
        return next
    }
}

class AddKeyframeCommand(
    private val clipId: String,
    private val keyframe: TimelineKeyframe
) : TimelineCommand {
    override fun apply(state: TimelineState): TimelineState = state.copy(
        clips = state.clips.map { clip ->
            if (clip.id == clipId) clip.copy(
                keyframes = (clip.keyframes.filterNot {
                    it.timeMs == keyframe.timeMs && it.property == keyframe.property
                } + keyframe).sortedBy { it.timeMs }
            ) else clip
        }
    )
}

/** Command history menyimpan state sebelum/sesudah setiap aksi. */
class TimelineStateManager(initial: TimelineState = TimelineState()) {
    var state: TimelineState = initial
        private set
    private val undoStates = ArrayDeque<TimelineState>()
    private val redoStates = ArrayDeque<TimelineState>()

    fun execute(command: TimelineCommand) {
        val next = command.apply(state)
        if (next == state) return
        undoStates.addLast(state)
        if (undoStates.size > 100) undoStates.removeFirst()
        state = next
        redoStates.clear()
    }

    fun undo(): Boolean {
        if (undoStates.isEmpty()) return false
        redoStates.addLast(state)
        state = undoStates.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (redoStates.isEmpty()) return false
        undoStates.addLast(state)
        state = redoStates.removeLast()
        return true
    }

    fun setPlayhead(positionMs: Long) {
        state = state.copy(playheadMs = positionMs.coerceIn(0L, state.durationMs()))
    }
}

private fun normalizeMainTrack(state: TimelineState): TimelineState {
    var cursor = 0L
    val main = state.clips.filter { it.trackIndex == 0 }.sortedBy { it.startTime }
    val normalized = main.map { clip ->
        clip.copy(startTime = cursor).also { cursor += clip.duration }
    }
    val overlays = state.clips.filter { it.trackIndex != 0 }
    return state.copy(clips = normalized + overlays)
}
