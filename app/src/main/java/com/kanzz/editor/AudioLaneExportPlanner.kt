package com.kanzz.editor

/**
 * Pure timeline planner for audio export.
 *
 * It converts lane clips into ordered segments with the silence required
 * before each clip. Media3 objects are deliberately created by the caller.
 */
data class AudioLaneExportSegment(
    val clip: AudioClip,
    val gapBeforeMs: Long
)

object AudioLaneExportPlanner {
    fun plan(clips: List<AudioClip>): List<AudioLaneExportSegment> {
        var cursorMs = 0L

        return clips
            .asSequence()
            .filter { it.durationMs > 0L && it.filePath.isNotBlank() }
            .sortedBy { it.startTimeMs }
            .map { clip ->
                val clipStartMs = clip.startTimeMs.coerceAtLeast(0L)
                val gapBeforeMs = (clipStartMs - cursorMs).coerceAtLeast(0L)
                cursorMs = maxOf(cursorMs, clipStartMs + clip.durationMs)
                AudioLaneExportSegment(clip, gapBeforeMs)
            }
            .toList()
    }
}
