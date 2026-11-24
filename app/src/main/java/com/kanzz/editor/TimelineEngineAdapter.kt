package com.kanzz.editor

/**
 * Read-only bridge from the production clip manager to the pure timeline engine.
 *
 * The existing VideoClipManager remains the source of truth during this first
 * integration step. No editor mutations are routed through TimelineStateManager
 * until parity tests cover every supported clip operation.
 */
object TimelineEngineAdapter {

    fun snapshotVideo(
        manager: VideoClipManager,
        playheadMs: Long,
        rippleMainTrack: Boolean = true
    ): TimelineState {
        val clips = manager.clips.map { clip ->
            val sourceDuration = clip.sourceDurationMs.coerceAtLeast(clip.sourceEndMs)
            val sourceStart = clip.sourceStartMs.coerceIn(0L, sourceDuration)
            val sourceEnd = clip.sourceEndMs.coerceIn(sourceStart, sourceDuration)

            EditorClip(
                id = clip.id,
                mediaUri = clip.sourceUri,
                trackIndex = 0,
                startTime = clip.timelineStartMs,
                duration = clip.timelineDurationMs,
                trimStart = sourceStart,
                trimEnd = (sourceDuration - sourceEnd).coerceAtLeast(0L),
                sourceDuration = sourceDuration
            )
        }

        val duration = clips.maxOfOrNull { it.endTime } ?: 0L
        return TimelineState(
            clips = clips,
            playheadMs = playheadMs.coerceIn(0L, duration),
            rippleMainTrack = rippleMainTrack
        )
    }
}
