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
    fun futureOrMalformedVideoVersionDoesNotReplaceTimeline() {
        val manager = VideoClipManager().apply {
            clips.add(VideoClip(id = "keep", sourceUri = "content://keep", sourceStartMs = 0L, durationMs = 1000L))
            recalculateTimeline()
        }
        val validClip = """{"id":"new","sourceUri":"content://new","sourceStartMs":0,"durationMs":1000}"""
        TimelineStateCodec.restoreVideo("""{"version":2,"clips":[$validClip]}""", manager)
        TimelineStateCodec.restoreVideo("""{"version":"one","clips":[$validClip]}""", manager)
        assertEquals(listOf("keep"), manager.clips.map { it.id })
    }

    @Test
    fun invalidVideoClipDoesNotPartiallyReplaceTimeline() {
        val manager = VideoClipManager().apply {
            clips.add(VideoClip(id = "keep", sourceUri = "content://keep", sourceStartMs = 0L, durationMs = 1000L))
            recalculateTimeline()
        }
        val valid = """{"id":"new","sourceUri":"content://new","sourceStartMs":0,"durationMs":1000}"""
        val invalid = """{"id":"bad","sourceUri":"content://bad","sourceStartMs":900,"durationMs":500,"sourceDurationMs":1000}"""
        TimelineStateCodec.restoreVideo("""{"version":1,"clips":[$valid,$invalid]}""", manager)
        assertEquals(listOf("keep"), manager.clips.map { it.id })
    }

    @Test
    fun invalidAudioClipDoesNotPartiallyReplaceTimeline() {
        val manager = AudioClipManager().apply {
            addClip(AudioClip(id = "keep-audio", startTimeMs = 0L, durationMs = 500L, filePath = "content://keep"))
        }
        val valid = """{"id":"new-audio","startTimeMs":0,"durationMs":500,"filePath":"content://new"}"""
        val invalid = """{"id":"bad-audio","startTimeMs":0,"durationMs":-5,"filePath":"content://bad"}"""
        TimelineStateCodec.restoreAudio("""{"version":1,"clips":[$valid,$invalid]}""", manager)
        assertEquals(listOf("keep-audio"), manager.clips.map { it.id })
    }

    @Test
    fun futureAudioVersionDoesNotReplaceTimeline() {
        val manager = AudioClipManager().apply {
            addClip(AudioClip(id = "keep-audio", startTimeMs = 0L, durationMs = 500L, filePath = "content://keep"))
        }
        TimelineStateCodec.restoreAudio("""{"version":3,"clips":[]}""", manager)
        assertEquals(listOf("keep-audio"), manager.clips.map { it.id })
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
