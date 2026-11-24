package com.kanzz.editor

// =====================================
// SHARED TIMELINE CONTROLLER
// =====================================

class SharedTimelineController {

    // Durasi proyek
    var durationMs = 0L

    // Posisi playhead
    var positionMs = 0L

    // Tingkat zoom
    var pixelsPerMs = 0.0005f

    // Posisi scroll
    var offsetPx = 0f

    // Lebar area timeline
    var viewportWidthPx = 0f


    // =================================
    // DURASI
    // =================================

    fun setDuration(duration: Long) {

        durationMs = duration.coerceAtLeast(0L)

        positionMs = positionMs.coerceIn(
            0L,
            durationMs
        )

        clampOffset()
    }


    // =================================
    // POSISI PLAYHEAD
    // =================================

    fun setPosition(position: Long) {

        positionMs = position.coerceIn(
            0L,
            durationMs
        )

        centerPlayhead()
    }


    // =================================
    // MEMUSATKAN PLAYHEAD
    // =================================

    fun centerPlayhead() {

        if (viewportWidthPx <= 0f) return

        offsetPx =
            positionMs * pixelsPerMs -
            viewportWidthPx / 2f

        clampOffset()
    }


    // =================================
    // ZOOM
    // =================================

    fun zoomAt(
        scaleFactor: Float,
        focusX: Float
    ) {
        if (pixelsPerMs <= 0f) return
        val focusTime = (offsetPx + focusX) / pixelsPerMs
        applyZoom(scaleFactor)
        offsetPx = focusTime * pixelsPerMs - focusX
        clampOffset()
    }

    // Zoom dengan waktu playhead sebagai jangkar tetap.
    fun zoomAroundPlayhead(scaleFactor: Float) {
        if (pixelsPerMs <= 0f || viewportWidthPx <= 0f) return
        val playheadTime = positionMs.toDouble()
        applyZoom(scaleFactor)
        offsetPx = (playheadTime * pixelsPerMs.toDouble() - viewportWidthPx / 2.0).toFloat()
        clampOffset()
    }

    private fun applyZoom(scaleFactor: Float) {
        val minScale = if (viewportWidthPx > 0f) viewportWidthPx / 30_000f else 0.00005f
        val maxScale = if (viewportWidthPx > 0f) viewportWidthPx / 2_000f else 0.05f
        pixelsPerMs = (pixelsPerMs * scaleFactor).coerceIn(minScale, maxScale)
    }


    // =================================
    // KONVERSI WAKTU KE POSISI LAYAR
    // =================================

    fun timeToX(timeMs: Long): Float {

        return timeMs * pixelsPerMs - offsetPx
    }


    // =================================
    // KONVERSI POSISI LAYAR KE WAKTU
    // =================================

    fun xToTime(x: Float): Long {

        if (pixelsPerMs <= 0f) return 0L

        return (
            (offsetPx + x) / pixelsPerMs
        ).toLong().coerceIn(
            0L,
            durationMs
        )
    }


    // =================================
    // BATAS SCROLL
    // =================================

    fun clampOffset() {

        if (durationMs <= 0L || pixelsPerMs <= 0f) return

        val minimumOffset = -viewportWidthPx / 2f

        val maximumOffset =
            durationMs * pixelsPerMs -
            viewportWidthPx / 2f

        offsetPx = offsetPx.coerceIn(
            minimumOffset,
            maximumOffset.coerceAtLeast(minimumOffset)
        )
    }
}
