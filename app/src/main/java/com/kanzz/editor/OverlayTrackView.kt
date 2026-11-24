package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Editable visual lane for image/video overlays. All coordinates use the shared
 * project timeline controller, so scrubbing stays aligned with other tracks.
 */
class OverlayTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val laneIndex: Int,
    private val allClips: () -> List<OverlayClip>,
    private val onSelected: (OverlayClip?) -> Unit,
    private val onMove: (OverlayClip, Long) -> Unit,
    private val onTrim: (OverlayClip, Long, Long) -> Unit,
    private val onChanged: () -> Unit,
    private val onTimelineScrolled: (Long) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val clips get() = allClips().filter { it.trackIndex == laneIndex }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF258B78.toInt() }
    private val imageFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF477FA8.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
        isFakeBoldText = true
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var selectedId: String? = null
    private var touched: OverlayClip? = null
    private var mode = 0 // 1 move, 2 trim left, 3 trim right
    private var downX = 0f
    private var lastX = 0f
    private var originStart = 0L
    private var originDuration = 0L
    private var previewStart = 0L
    private var previewDuration = 0L
    private var moved = false

    fun select(id: String?) {
        selectedId = id
        invalidate()
    }

    private fun hit(x: Float): OverlayClip? = clips.lastOrNull { clip ->
        val left = controller.timeToX(clip.startTimeMs)
        val right = controller.timeToX(clip.endTimeMs)
        x >= left && x <= right
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        clips.forEach { clip ->
            val start = if (clip.id == touched?.id && moved) previewStart else clip.startTimeMs
            val duration = if (clip.id == touched?.id && moved) previewDuration else clip.durationMs
            val left = controller.timeToX(start)
            val right = controller.timeToX(start + duration)
            if (right < 0f || left > width) return@forEach
            val rect = RectF(left, 3f * density, right.coerceAtLeast(left + 8f), height - 3f * density)
            canvas.drawRoundRect(
                rect, 4f * density, 4f * density,
                if (clip.mediaType == OverlayClip.Type.IMAGE) imageFill else fill
            )
            if (clip.id == selectedId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, border)
                val handleWidth = 7f * density
                canvas.drawRoundRect(
                    RectF(rect.left + 2f * density, rect.top + 5f * density,
                        rect.left + handleWidth, rect.bottom - 5f * density),
                    2f * density, 2f * density, handle
                )
                canvas.drawRoundRect(
                    RectF(rect.right - handleWidth, rect.top + 5f * density,
                        rect.right - 2f * density, rect.bottom - 5f * density),
                    2f * density, 2f * density, handle
                )
            }
            canvas.save()
            canvas.clipRect(rect)
            val title = if (clip.mediaType == OverlayClip.Type.IMAGE) "IMAGE" else "VIDEO"
            canvas.drawText(title, rect.left + 8f * density, rect.centerY() + 3.5f * density, label)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                lastX = event.x
                moved = false
                touched = hit(event.x)
                mode = 0
                touched?.let { clip ->
                    selectedId = clip.id
                    originStart = clip.startTimeMs
                    originDuration = clip.durationMs
                    previewStart = originStart
                    previewDuration = originDuration
                    val left = controller.timeToX(clip.startTimeMs)
                    val right = controller.timeToX(clip.endTimeMs)
                    val edge = 18f * density
                    mode = when {
                        event.x - left <= edge -> 2
                        right - event.x <= edge -> 3
                        else -> 1
                    }
                }
                onSelected(touched)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val clip = touched
                if (clip != null && controller.pixelsPerMs > 0f) {
                    val delta = ((event.x - downX) / controller.pixelsPerMs).toLong()
                    if (abs(event.x - downX) > 3f * density) moved = true
                    if (moved) {
                        when (mode) {
                            2 -> {
                                val end = originStart + originDuration
                                previewStart = (originStart + delta).coerceIn(0L, end - 100L)
                                previewDuration = end - previewStart
                            }
                            3 -> previewDuration = (originDuration + delta).coerceAtLeast(100L)
                            else -> previewStart = (originStart + delta).coerceAtLeast(0L)
                        }
                        invalidate()
                    }
                } else if (clip == null) {
                    val dx = event.x - lastX
                    if (abs(event.x - downX) > 5f * density) {
                        controller.offsetPx -= dx
                        controller.clampOffset()
                        val position = controller.xToTime(width / 2f)
                        controller.positionMs = position
                        onTimelineScrolled(position)
                        invalidate()
                    }
                }
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                val clip = touched
                if (clip != null && moved) {
                    when (mode) {
                        1 -> onMove(clip, previewStart)
                        else -> onTrim(clip, previewStart, previewDuration)
                    }
                    onChanged()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                mode = 0
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                mode = 0
                moved = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
