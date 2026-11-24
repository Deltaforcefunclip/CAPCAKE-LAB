package com.kanzz.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLaneExportPlannerTest {
    private fun audio(
        id: String,
        start: Long,
        duration: Long,
        path: String = "content://$id"
    ) = AudioClip(
        id = id,
        startTimeMs = start,
        durationMs = duration,
        filePath = path
    )

    @Test
    fun plannerSortsClipsAndCalculatesTimelineGaps() {
        val late = audio("late", 2500L, 500L)
        val early = audio("early", 1000L, 700L)
        val first = audio("first", 0L, 400L)

        val result = AudioLaneExportPlanner.plan(listOf(late, early, first))

        assertEquals(listOf("first", "early", "late"), result.map { it.clip.id })
        assertEquals(listOf(0L, 600L, 800L), result.map { it.gapBeforeMs })
    }

    @Test
    fun plannerClampsNegativeStartAndDoesNotCreateNegativeGapsForOverlap() {
        val negative = audio("negative", -300L, 500L)
        val overlap = audio("overlap", 100L, 600L)
        val after = audio("after", 1000L, 200L)

        val result = AudioLaneExportPlanner.plan(listOf(negative, overlap, after))

        assertEquals(listOf(0L, 0L, 300L), result.map { it.gapBeforeMs })
        assertEquals(listOf("negative", "overlap", "after"), result.map { it.clip.id })
    }

    @Test
    fun plannerSkipsEmptyAndInvalidClips() {
        val result = AudioLaneExportPlanner.plan(
            listOf(
                audio("zero", 0L, 0L),
                audio("blank", 100L, 200L, ""),
                audio("valid", 300L, 100L)
            )
        )

        assertEquals(1, result.size)
        assertEquals("valid", result.single().clip.id)
        assertTrue(result.single().gapBeforeMs >= 0L)
    }

    @Test
    fun plannerKeepsCursorAtFurthestEndWhenClipsOverlap() {
        val result = AudioLaneExportPlanner.plan(
            listOf(audio("long", 0L, 2000L), audio("short", 500L, 300L), audio("next", 2500L, 200L))
        )

        assertEquals(listOf(0L, 0L, 500L), result.map { it.gapBeforeMs })
    }
}
