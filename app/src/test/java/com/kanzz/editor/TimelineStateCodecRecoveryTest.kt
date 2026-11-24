package com.kanzz.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineStateCodecRecoveryTest {
    @Test
    fun malformedVideoPayloadDoesNotClearExistingTimeline() {
        val manager = VideoClipManager().apply {
            clips.add(
                VideoClip(
                    id = "keep-video",
                    sourceUri = "content://existing",
                    sourceStartMs = 0L,
                    durationMs = 1200L,
                    sourceDurationMs = 1200L
                )
            )
            recalculateTimeline()
        }

        TimelineStateCodec.restoreVideo("{}", manager)
        TimelineStateCodec.restoreVideo("{broken", manager)

        assertEquals(1, manager.clips.size)
        assertEquals("keep-video", manager.clips.single().id)
    }

    @Test
    fun malformedAudioPayloadDoesNotClearExistingTimeline() {
        val manager = AudioClipManager().apply {
            addClip(
                AudioClip(
                    id = "keep-audio",
                    startTimeMs = 0L,
                    durationMs = 900L,
                    filePath = "content://existing-audio"
                )
            )
        }

        TimelineStateCodec.restoreAudio("{}", manager)
        TimelineStateCodec.restoreAudio("{broken", manager)

        assertEquals(1, manager.clips.size)
        assertEquals("keep-audio", manager.clips.single().id)
        assertTrue(manager.lanes.isNotEmpty())
    }
}
