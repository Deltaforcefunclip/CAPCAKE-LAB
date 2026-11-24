package com.kanzz.editor

import org.junit.Assert.*
import org.junit.Test

class TimelineEngineAdapterTest {
    @Test fun snapshotMapsProductionClipOrderAndTimelinePositions() {
        val manager = VideoClipManager().apply {
            clips.add(VideoClip(
                id = "first",
                sourceUri = "content://first",
                sourceStartMs = 100L,
                durationMs = 400L,
                sourceDurationMs = 1000L
            ))
            clips.add(VideoClip(
                id = "second",
                sourceUri = "content://second",
                sourceStartMs = 0L,
                durationMs = 600L,
                sourceDurationMs = 600L,
                speed = 2f
            ))
            recalculateTimeline()
        }

        val state = TimelineEngineAdapter.snapshotVideo(manager, 9999L)

        assertEquals(listOf("first", "second"), state.clips.map { it.id })
        assertEquals(listOf(0L, 400L), state.clips.map { it.startTime })
        assertEquals(listOf(400L, 300L), state.clips.map { it.duration })
        assertEquals(100L, state.clips.first().sourceStart)
        assertEquals(500L, state.clips.first().sourceEnd)
        assertEquals(1000L, state.clips.first().sourceDuration)
        assertEquals(700L, state.playheadMs)
        assertTrue(state.rippleMainTrack)
    }

    @Test fun snapshotClampsPlayheadAndPreservesEmptyTimeline() {
        val empty = TimelineEngineAdapter.snapshotVideo(VideoClipManager(), 500L)
        assertTrue(empty.clips.isEmpty())
        assertEquals(0L, empty.playheadMs)

        val manager = VideoClipManager().apply { setSource("content://one", 250L) }
        val before = TimelineEngineAdapter.snapshotVideo(manager, -50L)
        val after = TimelineEngineAdapter.snapshotVideo(manager, 500L)

        assertEquals(0L, before.playheadMs)
        assertEquals(250L, after.playheadMs)
    }
}
