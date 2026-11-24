package com.kanzz.editor

import org.junit.Assert.*
import org.junit.Test

class OverlayStateCodecTest {
    private fun manager() = OverlayClipManager().apply {
        add(OverlayClip(
            id = "video-one",
            sourceUri = "content://video",
            mediaType = OverlayClip.Type.VIDEO,
            startTimeMs = 1000L,
            durationMs = 2000L,
            sourceStartMs = 250L,
            sourceDurationMs = 2000L,
            positionX = 12.5f,
            positionY = -8f,
            scale = 1.5f,
            rotation = 25f,
            opacity = 0.75f
        ))
        add(OverlayClip(
            id = "image-one",
            sourceUri = "content://image",
            mediaType = OverlayClip.Type.IMAGE,
            startTimeMs = 500L,
            durationMs = 3000L
        ))
    }

    @Test fun roundTripPreservesOverlayMediaAndTransformProperties() {
        val source = manager()
        val restored = OverlayClipManager()

        assertTrue(OverlayStateCodec.restore(OverlayStateCodec.encode(source), restored))
        assertEquals(2, restored.clips.size)
        val video = restored.find("video-one")!!
        assertEquals(OverlayClip.Type.VIDEO, video.mediaType)
        assertEquals("content://video", video.sourceUri)
        assertEquals(1000L, video.startTimeMs)
        assertEquals(250L, video.sourceStartMs)
        assertEquals(2000L, video.sourceDurationMs)
        assertEquals(12.5f, video.positionX, 0.001f)
        assertEquals(-8f, video.positionY, 0.001f)
        assertEquals(1.5f, video.scale, 0.001f)
        assertEquals(25f, video.rotation, 0.001f)
        assertEquals(0.75f, video.opacity, 0.001f)
        assertEquals(OverlayClip.Type.IMAGE, restored.find("image-one")!!.mediaType)
    }

    @Test fun legacyPayloadWithoutOptionalTransformFieldsRestoresDefaults() {
        val json = """{"version":1,"clips":[{"id":"legacy","sourceUri":"content://image","mediaType":"IMAGE","startTimeMs":0,"durationMs":1500}]}"""
        val restored = OverlayClipManager()

        assertTrue(OverlayStateCodec.restore(json, restored))
        val clip = restored.find("legacy")!!
        assertEquals(0f, clip.positionX, 0.001f)
        assertEquals(0f, clip.positionY, 0.001f)
        assertEquals(1f, clip.scale, 0.001f)
        assertEquals(1f, clip.opacity, 0.001f)
        assertEquals(1500L, clip.sourceDurationMs)
    }

    @Test fun corruptOrIncompletePayloadDoesNotClearExistingManager() {
        val restored = manager()
        val before = OverlayStateCodec.encode(restored)

        assertFalse(OverlayStateCodec.restore("{", restored))
        assertEquals(before, OverlayStateCodec.encode(restored))
        assertFalse(OverlayStateCodec.restore("""{"version":1}""", restored))
        assertEquals(before, OverlayStateCodec.encode(restored))
    }

    @Test fun restoreRejectsCollidingLaneAndInvalidRangesAtomically() {
        val restored = manager()
        val before = OverlayStateCodec.encode(restored)
        val collision = """{"clips":[
            {"id":"a","sourceUri":"content://a","mediaType":"IMAGE","startTimeMs":0,"durationMs":1000,"trackIndex":0},
            {"id":"b","sourceUri":"content://b","mediaType":"IMAGE","startTimeMs":500,"durationMs":1000,"trackIndex":0}
        ]}"""
        val invalid = """{"clips":[{"id":"bad","sourceUri":"content://bad","mediaType":"VIDEO","startTimeMs":0,"durationMs":0}]}"""

        assertFalse(OverlayStateCodec.restore(collision, restored))
        assertEquals(before, OverlayStateCodec.encode(restored))
        assertFalse(OverlayStateCodec.restore(invalid, restored))
        assertEquals(before, OverlayStateCodec.encode(restored))
    }

    @Test fun restoreClampsSafeTransformRanges() {
        val json = """{"clips":[{"id":"safe","sourceUri":"content://safe","mediaType":"IMAGE","startTimeMs":0,"durationMs":1000,"scale":99,"opacity":-2}]}"""
        val restored = OverlayClipManager()

        assertTrue(OverlayStateCodec.restore(json, restored))
        assertEquals(10f, restored.find("safe")!!.scale, 0.001f)
        assertEquals(0f, restored.find("safe")!!.opacity, 0.001f)
    }
}
