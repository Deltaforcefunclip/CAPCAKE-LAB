package com.kanzz.editor

import org.junit.Assert.*
import org.junit.Test

class OverlayClipManagerTest {
    private fun overlay(
        id: String,
        start: Long,
        duration: Long,
        lane: Int = 0,
        type: OverlayClip.Type = OverlayClip.Type.VIDEO,
        sourceDuration: Long = duration
    ) = OverlayClip(
        id = id,
        sourceUri = "content://$id",
        mediaType = type,
        startTimeMs = start,
        durationMs = duration,
        sourceDurationMs = sourceDuration,
        trackIndex = lane
    )

    @Test fun addAssignsFirstAvailableNonOverlappingLaneAndClampsValues() {
        val manager = OverlayClipManager()
        val first = overlay("a", 0L, 1000L)
        val overlap = overlay("b", 500L, 1000L)
        val separate = overlay("c", 1000L, 500L)

        manager.add(first)
        manager.add(overlap)
        manager.add(separate)

        assertEquals(0, first.trackIndex)
        assertEquals(1, overlap.trackIndex)
        assertEquals(0, separate.trackIndex)
        assertEquals(listOf("a", "c", "b"), manager.clips.map { it.id })
    }

    @Test fun moveRejectsCollisionAndPreservesOriginalPosition() {
        val manager = OverlayClipManager()
        val first = overlay("a", 0L, 1000L)
        val second = overlay("b", 1500L, 1000L)
        manager.add(first)
        manager.add(second)

        assertFalse(manager.moveClip("b", 500L, 0))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(0, second.trackIndex)
        assertTrue(manager.moveClip("b", 500L, 1))
        assertEquals(500L, second.startTimeMs)
        assertEquals(1, second.trackIndex)
    }

    @Test fun moveToLaneRejectsInvalidLaneAndCollision() {
        val manager = OverlayClipManager()
        val first = overlay("a", 0L, 1000L)
        val second = overlay("b", 500L, 500L)
        manager.add(first)
        manager.add(second)

        assertFalse(manager.moveClipToLane("b", -1))
        assertFalse(manager.moveClipToLane("b", 0))
        assertTrue(manager.moveClipToLane("b", 2))
        assertEquals(2, second.trackIndex)
    }

    @Test fun splitVideoPreservesSourceContinuityAndTimelineRange() {
        val manager = OverlayClipManager()
        val clip = overlay("video", 1000L, 2000L, sourceDuration = 2000L)
        manager.add(clip)

        val second = manager.split("video", 1750L)

        assertNotNull(second)
        assertEquals(750L, clip.durationMs)
        assertEquals(1250L, second!!.durationMs)
        assertEquals(750L, clip.sourceDurationMs)
        assertEquals(750L, second.sourceStartMs)
        assertEquals(1250L, second.sourceDurationMs)
        assertEquals(1750L, second.startTimeMs)
    }

    @Test fun videoSourceDurationStaysAlignedAfterOddLengthSplit() {
        val manager = OverlayClipManager()
        val clip = overlay("odd", 0L, 2001L, sourceDuration = 2000L)
        manager.add(clip)

        assertEquals(2001L, clip.sourceDurationMs)
        val second = manager.split("odd", 1000L)

        assertNotNull(second)
        assertEquals(1000L, clip.sourceDurationMs)
        assertEquals(1001L, second!!.sourceStartMs)
        assertEquals(1001L, second.sourceDurationMs)
        assertEquals(clip.durationMs, clip.sourceDurationMs)
        assertEquals(second.durationMs, second.sourceDurationMs)
    }

    @Test fun splitImageKeepsSameSourceAndRejectsBoundaries() {
        val manager = OverlayClipManager()
        val clip = overlay("image", 200L, 1000L, type = OverlayClip.Type.IMAGE)

        manager.add(clip)
        assertNull(manager.split("image", 200L))
        assertNull(manager.split("image", 1200L))
        val second = manager.split("image", 700L)

        assertNotNull(second)
        assertEquals(0L, second!!.sourceStartMs)
        assertEquals(1000L, second.sourceDurationMs)
        assertEquals(500L, clip.durationMs)
    }

    @Test fun trimRejectsInvalidRangeAndCollisionWithoutMutatingClip() {
        val manager = OverlayClipManager()
        val first = overlay("a", 0L, 1000L)
        val second = overlay("b", 1500L, 500L)
        manager.add(first)
        manager.add(second)

        assertFalse(manager.trim("b", 500L, 1000L))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(500L, second.durationMs)
        assertFalse(manager.trim("b", -1L, 100L))
        assertTrue(manager.trim("b", 1500L, 400L))
        assertEquals(400L, second.durationMs)
    }

    @Test fun trimVideoUpdatesSourceRangeAndRejectsInvalidExtension() {
        val manager = OverlayClipManager()
        val clip = overlay("trim", 1000L, 2000L, sourceDuration = 2000L)
        manager.add(clip)

        assertTrue(manager.trim("trim", 1500L, 1000L))
        assertEquals(1500L, clip.startTimeMs)
        assertEquals(1000L, clip.durationMs)
        assertEquals(500L, clip.sourceStartMs)
        assertEquals(1000L, clip.sourceDurationMs)

        assertFalse(manager.trim("trim", 1500L, 1500L))
        assertEquals(500L, clip.sourceStartMs)
        assertEquals(1000L, clip.sourceDurationMs)
    }

    @Test fun trimImageChangesTimelineButKeepsImageSourceRange() {
        val manager = OverlayClipManager()
        val clip = overlay("image-trim", 0L, 3000L, type = OverlayClip.Type.IMAGE)
        manager.add(clip)

        assertTrue(manager.trim("image-trim", 500L, 1500L))
        assertEquals(0L, clip.sourceStartMs)
        assertEquals(3000L, clip.sourceDurationMs)
    }

    @Test fun removeCompactsLanesAndUnknownIdsAreSafe() {
        val manager = OverlayClipManager()
        val first = overlay("a", 0L, 500L, lane = 0)
        val second = overlay("b", 0L, 500L, lane = 1)
        val third = overlay("c", 0L, 500L, lane = 2)
        manager.clips.addAll(listOf(first, second, third))

        assertTrue(manager.remove("b"))
        assertEquals(listOf(0, 1), manager.clips.map { it.trackIndex }.distinct().sorted())
        assertFalse(manager.remove("missing"))
    }

    @Test fun newBottomLaneMovesClipWithoutChangingTime() {
        val manager = OverlayClipManager()
        val clip = overlay("a", 350L, 700L)
        manager.add(clip)

        assertTrue(manager.moveClipToNewBottomLane("a"))
        assertEquals(350L, clip.startTimeMs)
        assertEquals(0, clip.trackIndex)
    }

    @Test fun movePreflightRejectsCollisionWithoutMutatingTimeline() {
        val manager = OverlayClipManager()
        val first = overlay("first", 0L, 1000L)
        val second = overlay("second", 1500L, 500L)
        manager.add(first)
        manager.add(second)

        assertFalse(manager.canMove("second", 500L, 0))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(0, second.trackIndex)
        assertTrue(manager.canMove("second", 500L, 1))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(0, second.trackIndex)
    }

    @Test fun trimPreflightRejectsCollisionAndExtensionWithoutMutatingSourceRange() {
        val manager = OverlayClipManager()
        val first = overlay("first", 0L, 1000L)
        val second = overlay("second", 1500L, 1000L, sourceDuration = 1000L)
        manager.add(first)
        manager.add(second)

        assertFalse(manager.canTrim("second", 500L, 1000L))
        assertFalse(manager.canTrim("second", 1500L, 1500L))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(1000L, second.durationMs)
        assertEquals(1000L, second.sourceDurationMs)
        assertTrue(manager.canTrim("second", 1600L, 800L))
        assertEquals(1500L, second.startTimeMs)
        assertEquals(1000L, second.durationMs)
        assertEquals(1000L, second.sourceDurationMs)
    }
}
