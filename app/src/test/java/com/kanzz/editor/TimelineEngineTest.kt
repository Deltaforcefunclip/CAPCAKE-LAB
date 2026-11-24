package com.kanzz.editor

import org.junit.Assert.*
import org.junit.Test

class TimelineEngineTest {
    private fun clip(
        id: String,
        start: Long,
        duration: Long,
        track: Int = 0,
        sourceDuration: Long = duration,
        trimStart: Long = 0,
        trimEnd: Long = 0
    ) = EditorClip(
        id = id,
        mediaUri = "content://$id",
        trackIndex = track,
        startTime = start,
        duration = duration,
        trimStart = trimStart,
        trimEnd = trimEnd,
        sourceDuration = sourceDuration
    )

    @Test fun splitInsideClipPreservesSourceRanges() {
        val state = TimelineState(clips = listOf(clip("a", 0, 1000, sourceDuration = 1000)))
        val result = SplitClipCommand("a", 400, "b").apply(state)
        assertEquals(listOf(400L, 600L), result.clips.map { it.duration })
        assertEquals(0L, result.clips[0].sourceStart)
        assertEquals(400L, result.clips[0].sourceEnd)
        assertEquals(400L, result.clips[1].sourceStart)
        assertEquals(1000L, result.clips[1].sourceEnd)
    }

    @Test fun splitAtOrOutsideBoundaryIsNoOp() {
        val original = TimelineState(clips = listOf(clip("a", 100, 500)))
        assertEquals(original, SplitClipCommand("a", 100, "b").apply(original))
        assertEquals(original, SplitClipCommand("a", 600, "b").apply(original))
        assertEquals(original, SplitClipCommand("missing", 300, "b").apply(original))
    }

    @Test fun rippleDeleteClosesMainTrackGapOnly() {
        val state = TimelineState(clips = listOf(
            clip("a", 0, 1000), clip("b", 1000, 500), clip("c", 1500, 700),
            clip("overlay", 1000, 200, track = 1)
        ))
        val result = DeleteClipCommand("b").apply(state)
        assertEquals(800L, result.clip("c")!!.startTime)
        assertEquals(1000L, result.clip("overlay")!!.startTime)
    }

    @Test fun moveRejectsOverlayCollision() {
        val state = TimelineState(clips = listOf(
            clip("a", 0, 500, track = 1), clip("b", 700, 500, track = 1)
        ))
        val result = MoveClipCommand("b", 1, 300).apply(state)
        assertEquals(state, result)
    }

    @Test fun snapUsesClipEdgesAndScreenTolerance() {
        val state = TimelineState(clips = listOf(clip("a", 0, 1000), clip("b", 2000, 500)))
        val result = TimelineSnapEngine.snap(
            proposedTimeMs = 1495, movingClipId = "b", state = state,
            pixelsPerMs = 0.1f, tolerancePx = 10f
        )
        assertTrue(result.snapped)
        assertEquals(1500L, result.timeMs)
    }

    @Test fun undoRedoRestoresExactTimelineState() {
        val initial = TimelineState(clips = listOf(clip("a", 0, 1000)))
        val manager = TimelineStateManager(initial)
        manager.execute(SplitClipCommand("a", 400, "b"))
        assertEquals(2, manager.state.clips.size)
        assertTrue(manager.undo())
        assertEquals(initial, manager.state)
        assertTrue(manager.redo())
        assertEquals(2, manager.state.clips.size)
    }

    @Test fun keyframeAtSameTimeAndPropertyIsReplaced() {
        val state = TimelineState(clips = listOf(clip("a", 0, 1000)))
        val first = TimelineKeyframe("k1", 250, "opacity", 0.2f)
        val replacement = TimelineKeyframe("k2", 250, "opacity", 0.8f)
        val once = AddKeyframeCommand("a", first).apply(state)
        val twice = AddKeyframeCommand("a", replacement).apply(once)
        assertEquals(1, twice.clip("a")!!.keyframes.size)
        assertEquals("k2", twice.clip("a")!!.keyframes.single().id)
        assertEquals(0.8f, twice.clip("a")!!.keyframes.single().value)
    }

    @Test fun virtualizerReturnsOnlyIntersectingClipsInTrackOrder() {
        val state = TimelineState(clips = listOf(
            clip("late", 2000, 500), clip("overlay", 900, 300, track = 1),
            clip("visible", 800, 300), clip("early", 0, 300)
        ))
        val visible = TimelineVirtualizer.visibleClips(state, 750, 1250)
        assertEquals(listOf("visible", "overlay"), visible.map { it.id })
    }

    @Test fun trimNeverMovesSourceRangeOutsideMedia() {
        val state = TimelineState(clips = listOf(
            clip("a", 0, 600, sourceDuration = 1000, trimStart = 200, trimEnd = 200)
        ))
        val result = TrimClipCommand("a", leftDeltaMs = 100, rightDeltaMs = -100).apply(state)
        val updated = result.clip("a")!!
        assertTrue(updated.sourceStart >= 0)
        assertTrue(updated.sourceEnd <= updated.sourceDuration)
        assertTrue(updated.duration > 0)
    }
}
