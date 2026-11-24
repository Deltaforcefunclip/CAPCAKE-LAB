package com.kanzz.editor

import org.junit.Assert.*
import org.junit.Test

class ExistingClipManagersTest {
    private fun managerWith(vararg clips: VideoClip): VideoClipManager =
        VideoClipManager().apply {
            this.clips.addAll(clips)
            recalculateTimeline()
        }

    private fun video(id: String, duration: Long, speed: Float = 1f) = VideoClip(
        id = id,
        sourceUri = "content://$id",
        sourceStartMs = 0L,
        durationMs = duration,
        sourceDurationMs = duration,
        speed = speed
    )

    @Test fun replacingMissingSourceUriPreservesAllClipEditsAndOnlyChangesMatchingClips() {
        val manager = managerWith(
            video("first", 1200L, 0.5f).copy(
                sourceUri = "content://missing",
                sourceStartMs = 200L,
                brightness = 0.4f,
                transformScale = 1.8f
            ),
            video("other", 800L).copy(sourceUri = "content://other")
        )

        assertTrue(manager.setTransition("first", "other", VideoTransition.Type.FADE, 300L))

        val replaced = manager.replaceSourceUri("content://missing", "content://restored")

        assertEquals(1, replaced)
        assertEquals("content://restored", manager.clips[0].sourceUri)
        assertEquals(200L, manager.clips[0].sourceStartMs)
        assertEquals(1200L, manager.clips[0].durationMs)
        assertEquals(0.5f, manager.clips[0].speed)
        assertEquals(0.4f, manager.clips[0].brightness)
        assertEquals(1.8f, manager.clips[0].transformScale)
        assertEquals("content://other", manager.clips[1].sourceUri)
        assertEquals(VideoTransition.Type.FADE, manager.transitionBetween("first", "other")?.type)
        assertEquals(300L, manager.transitionBetween("first", "other")?.durationMs)
        assertEquals(0, manager.replaceSourceUri("content://missing", "content://new"))
    }

    @Test fun splitUsesTimelineTimeAndPreservesSourceContinuityAtHalfSpeed() {
        val manager = managerWith(video("source", 2000L, 0.5f))

        val second = manager.split("source", 1000L)

        assertNotNull(second)
        assertEquals(500L, manager.clips[0].durationMs)
        assertEquals(1500L, second!!.durationMs)
        assertEquals(500L, second.sourceStartMs)
        assertEquals(4000L, manager.durationMs)
        assertEquals(1000L, second.timelineStartMs)
    }

    @Test fun splitRejectsTimelineBoundariesAndUnknownClip() {
        val manager = managerWith(video("source", 1000L))

        assertNull(manager.split("source", 0L))
        assertNull(manager.split("source", 1000L))
        assertNull(manager.split("missing", 500L))
        assertEquals(1, manager.clips.size)
    }

    @Test fun removingMainTrackClipClosesGapAndRemovesOrphanTransitions() {
        val first = video("a", 1000L)
        val second = video("b", 500L)
        val third = video("c", 700L)
        val manager = managerWith(first, second, third)
        manager.setTransition("a", "b", VideoTransition.Type.FADE, 250L)
        manager.setTransition("b", "c", VideoTransition.Type.FADE, 250L)

        assertTrue(manager.remove("b"))

        assertEquals(listOf("a", "c"), manager.clips.map { it.id })
        assertEquals(1000L, manager.clips[1].timelineStartMs)
        assertTrue(manager.transitions.isEmpty())
        assertFalse(manager.remove("missing"))
    }

    @Test fun trimRespectsSourceBoundsAndKeepsTimelinePacked() {
        val first = video("a", 1000L)
        val second = video("b", 500L)
        val manager = managerWith(first, second)

        assertTrue(manager.trimClipEdges("a", leftDeltaTimelineMs = 200L, rightDeltaTimelineMs = -300L))

        assertEquals(200L, first.sourceStartMs)
        assertEquals(500L, first.durationMs)
        assertEquals(500L, second.timelineStartMs)
        assertFalse(manager.trimClipEdges("a", 0L, 0L))
    }

    @Test fun movingClipReordersMainTrackWithoutGaps() {
        val manager = managerWith(video("a", 500L), video("b", 700L), video("c", 300L))

        assertTrue(manager.moveToTimelinePosition("c", 0L))

        assertEquals(listOf("c", "a", "b"), manager.clips.map { it.id })
        assertEquals(listOf(0L, 300L, 800L), manager.clips.map { it.timelineStartMs })
    }

    @Test fun transitionIsLimitedToAdjacentClipsAndDurationIsClamped() {
        val manager = managerWith(video("a", 500L), video("b", 500L), video("c", 500L))

        assertFalse(manager.setTransition("a", "c", VideoTransition.Type.FADE, 500L))
        assertTrue(manager.setTransition("a", "b", VideoTransition.Type.FADE, 9000L))
        assertEquals(3000L, manager.transitionBetween("a", "b")!!.durationMs)
        assertTrue(manager.setTransition("a", "b", VideoTransition.Type.NONE, 100L))
        assertEquals(100L, manager.transitionBetween("a", "b")!!.durationMs)
    }

    @Test fun audioManagerAssignsOverlappingClipsToDifferentLanes() {
        val manager = AudioClipManager()
        val first = AudioClip(id = "a", startTimeMs = 0L, durationMs = 1000L, filePath = "/a.wav")
        val overlap = AudioClip(id = "b", startTimeMs = 500L, durationMs = 1000L, filePath = "/b.wav")
        val separate = AudioClip(id = "c", startTimeMs = 1200L, durationMs = 300L, filePath = "/c.wav")

        manager.addClip(first)
        manager.addClip(overlap)
        manager.addClip(separate)

        assertEquals(2, manager.lanes.size)
        assertEquals(3, manager.clips.size)
        assertEquals(2, manager.lanes.first().clips.size)
    }
}
