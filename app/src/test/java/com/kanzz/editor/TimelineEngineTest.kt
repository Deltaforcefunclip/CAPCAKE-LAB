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
        assertEquals(1000L, result.clip("c")!!.startTime)
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
        val state = TimelineState(clips = listOf(clip("a", 0, 1000), clip("b", 4000, 500), clip("c", 2000, 500)))
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
        assertEquals(0.8f, twice.clip("a")!!.keyframes.single().value, 0.0001f)
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
    @Test fun corruptVideoProjectDoesNotClearExistingTimeline() {
        val manager = VideoClipManager().apply { setSource("content://existing", 900) }
        TimelineStateCodec.restoreVideo("{not valid json", manager)
        assertEquals(1, manager.clips.size)
        assertEquals("content://existing", manager.clips.single().sourceUri)
    }

    @Test fun legacyVideoProjectUsesSafeDefaults() {
        val manager = VideoClipManager()
        val legacy = """{"clips":[{"id":"legacy","sourceUri":"content://legacy","durationMs":700}]}"""
        TimelineStateCodec.restoreVideo(legacy, manager)
        assertEquals(1, manager.clips.size)
        assertEquals(700L, manager.clips.single().sourceDurationMs)
        assertEquals(1f, manager.clips.single().speed, 0.0001f)
        assertEquals(1f, manager.clips.single().opacity, 0.0001f)
    }

    @Test fun corruptAudioProjectDoesNotClearExistingTimeline() {
        val manager = AudioClipManager().apply {
            addClip(AudioClip(id = "existing", startTimeMs = 0, durationMs = 500, filePath = "/audio.wav"))
        }
        TimelineStateCodec.restoreAudio("{broken", manager)
        assertEquals(1, manager.clips.size)
        assertEquals("existing", manager.clips.single().id)
    }

    @Test fun legacyAudioProjectRestoresIntoLane() {
        val manager = AudioClipManager()
        val legacy = """{"clips":[{"id":"audio-1","startTimeMs":100,"durationMs":600,"filePath":"/audio.wav"}]}"""
        TimelineStateCodec.restoreAudio(legacy, manager)
        assertEquals(1, manager.clips.size)
        assertEquals(1, manager.lanes.size)
        assertEquals("audio-1", manager.lanes.single().clips.single().id)
    }

    @Test fun movingMainTrackClipChangesOrderWithoutCreatingGaps() {
        val state = TimelineState(clips = listOf(
            clip("a", 0, 500), clip("b", 500, 700), clip("c", 1200, 300)
        ))
        val result = MoveClipCommand("c", 0, 0).apply(state)
        assertEquals(listOf("c", "a", "b"), result.clips.filter { it.trackIndex == 0 }.map { it.id })
        assertEquals(listOf(0L, 300L, 800L), result.clips.filter { it.trackIndex == 0 }.map { it.startTime })
    }


    @Test fun videoCodecRoundTripPreservesClipOrderEffectsAndTransitions() {
        val source = VideoClipManager().apply {
            clips.add(VideoClip(
                id = "v1", sourceUri = "content://one", sourceStartMs = 120L,
                durationMs = 600L, sourceDurationMs = 1200L,
                speed = 1.5f, opacity = 0.75f, filterName = "Vintage",
                motionBlurIntensity = 0.4f,
                colorGrading = ColorGrading(exposure = 0.3f, blueSaturation = -0.2f)
            ))
            clips.add(VideoClip(
                id = "v2", sourceUri = "content://two", sourceStartMs = 0L,
                durationMs = 400L, sourceDurationMs = 400L
            ))
            recalculateTimeline()
            setTransition("v1", "v2", VideoTransition.Type.FADE, 350L)
        }

        val restored = VideoClipManager()
        TimelineStateCodec.restoreVideo(TimelineStateCodec.encodeVideo(source), restored)

        assertEquals(listOf("v1", "v2"), restored.clips.map { it.id })
        assertEquals(120L, restored.clips.first().sourceStartMs)
        assertEquals(1.5f, restored.clips.first().speed, 0.0001f)
        assertEquals(0.75f, restored.clips.first().opacity, 0.0001f)
        assertEquals("Vintage", restored.clips.first().filterName)
        assertEquals(0.3f, restored.clips.first().colorGrading.exposure, 0.0001f)
        assertEquals(-0.2f, restored.clips.first().colorGrading.blueSaturation, 0.0001f)
        assertEquals(VideoTransition.Type.FADE, restored.transitionBetween("v1", "v2")!!.type)
        assertEquals(350L, restored.transitionBetween("v1", "v2")!!.durationMs)
    }

    @Test fun videoCodecClampsUnsafeMotionBlurAndTransformValues() {
        val manager = VideoClipManager()
        val unsafe = """{"clips":[{"id":"safe","sourceUri":"content://safe","durationMs":500,"sourceDurationMs":500,"transformScale":99,"motionBlurIntensity":-4,"motionBlurBlend":3,"motionBlurDirection":9,"motionBlurTimes":3}]}"""
        TimelineStateCodec.restoreVideo(unsafe, manager)

        val clip = manager.clips.single()
        assertEquals(5f, clip.transformScale, 0.0001f)
        assertEquals(0f, clip.motionBlurIntensity, 0.0001f)
        assertEquals(1f, clip.motionBlurBlend, 0.0001f)
        assertEquals(2, clip.motionBlurDirection)
        assertEquals(4, clip.motionBlurTimes)
    }

    @Test fun audioCodecRoundTripPreservesLaneAssignmentAndClipSettings() {
        val source = AudioClipManager()
        source.addClip(AudioClip(
            id = "music", startTimeMs = 0L, durationMs = 1200L,
            filePath = "/music.wav", sourceStartMs = 100L,
            name = "Music", volume = 0.65f, speed = 1.25f,
            sourceDurationMs = 3000L
        ))
        source.addClip(AudioClip(
            id = "voice", startTimeMs = 200L, durationMs = 500L,
            filePath = "/voice.wav", name = "Voice"
        ))

        val restored = AudioClipManager()
        TimelineStateCodec.restoreAudio(TimelineStateCodec.encodeAudio(source), restored)

        assertEquals(2, restored.clips.size)
        assertEquals(2, restored.lanes.size)
        assertEquals("music", restored.lanes[0].clips.single().id)
        assertEquals("voice", restored.lanes[1].clips.single().id)
        assertEquals(100L, restored.clips.first { it.id == "music" }.sourceStartMs)
        assertEquals(0.65f, restored.clips.first { it.id == "music" }.volume, 0.0001f)
        assertEquals(1.25f, restored.clips.first { it.id == "music" }.speed, 0.0001f)
    }

    @Test fun audioCodecBoundsCorruptLaneIndexToClipCount() {
        val manager = AudioClipManager()
        val corrupt = """{"clips":[{"id":"a","startTimeMs":0,"durationMs":500,"filePath":"/a.wav","laneIndex":2147483647}]}"""

        TimelineStateCodec.restoreAudio(corrupt, manager)

        assertEquals(1, manager.clips.size)
        assertEquals(1, manager.lanes.size)
        assertEquals("a", manager.lanes.single().clips.single().id)
    }

    @Test fun audioCodecRestoresMissingLaneMetadataWithoutDroppingClips() {
        val manager = AudioClipManager()
        val legacy = """{"clips":[{"id":"a","startTimeMs":0,"durationMs":500,"filePath":"/a.wav"},{"id":"b","startTimeMs":100,"durationMs":200,"filePath":"/b.wav"}]}"""
        TimelineStateCodec.restoreAudio(legacy, manager)

        assertEquals(2, manager.clips.size)
        assertEquals(2, manager.lanes.size)
        assertTrue(manager.lanes.all { it.clips.size == 1 })
    }

}
