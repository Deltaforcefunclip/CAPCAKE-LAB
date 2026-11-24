package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

data class EffectClip(
    val id: String = java.util.UUID.randomUUID().toString(),
    var effectName: String,
    var startTimeMs: Long,
    var durationMs: Long = 2000L,
    var trackIndex: Int = 0,
    var opacity: Float = 1f,
    var intensity: Float = 0.45f,
    var glowSpread: Float = 0.35f,
    var warmth: Float = 0.5f,
    var softFocus: Float = 0.35f
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

class EffectClipManager {
    val clips = mutableListOf<EffectClip>()

    fun add(clip: EffectClip) {
        val end = clip.endTimeMs
        var lane = 0
        while (clips.any { it.trackIndex == lane && clip.startTimeMs < it.endTimeMs && end > it.startTimeMs }) lane++
        clip.trackIndex = lane
        clips.add(clip)
        sort()
    }

    fun find(id: String?) = clips.firstOrNull { it.id == id }
    fun canMoveToLane(clip: EffectClip, lane: Int): Boolean =
        clips.none { other -> other.id != clip.id && other.trackIndex == lane &&
            clip.startTimeMs < other.endTimeMs && clip.endTimeMs > other.startTimeMs }
    fun moveClipToLane(id: String, lane: Int): Boolean {
        val clip = find(id) ?: return false
        if (lane < 0 || !canMoveToLane(clip, lane)) return false
        clip.trackIndex = lane
        sort()
        return true
    }
    fun moveClipToNewBottomLane(id: String): Boolean {
        val clip = find(id) ?: return false
        clip.trackIndex = (clips.maxOfOrNull { it.trackIndex } ?: -1) + 1
        compactLanes()
        return true
    }
    fun remove(id: String?) { clips.removeAll { it.id == id }; compactLanes() }

    fun compactLanes() {
        val order = clips.map { it.trackIndex }.distinct().sorted()
        val remap = order.mapIndexed { index, old -> old to index }.toMap()
        clips.forEach { it.trackIndex = remap[it.trackIndex] ?: 0 }
        sort()
    }

    fun split(id: String?, positionMs: Long): EffectClip? {
        val clip = find(id) ?: return null
        if (positionMs <= clip.startTimeMs || positionMs >= clip.endTimeMs) return null
        val second = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            startTimeMs = positionMs,
            durationMs = clip.endTimeMs - positionMs
        )
        clip.durationMs = positionMs - clip.startTimeMs
        add(second)
        return second
    }

    private fun sort() {
        clips.sortWith(compareBy<EffectClip> { it.trackIndex }.thenBy { it.startTimeMs })
    }
}

/** Timeline track for effect clips. It shares the editor's single timeline controller. */
class EffectTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val laneIndex: Int,
    private val allClips: () -> List<EffectClip>,
    private val onSelected: (EffectClip?) -> Unit,
    private val onEditStarted: () -> Unit,
    private val onChanged: () -> Unit,
    private val onTimelineScrolled: (Long) -> Unit,
    private val onClipDrag: (EffectClip, Float) -> Unit,
    private val onClipDropped: (EffectClip, Float) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val clips get() = allClips().filter { it.trackIndex == laneIndex }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E63D9.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10f * density; isFakeBoldText = true
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var selectedId: String? = null
    private var touched: EffectClip? = null
    private var downX = 0f
    private var lastX = 0f
    private var originStart = 0L
    private var originDuration = 0L
    private var mode = 0 // 1 move, 2 trim start, 3 trim end
    private var moved = false
    private var editStarted = false
    private var downY = 0f
    private var dragOriginX = 0f
    private var dragOriginTimeMs = 0L
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (touched != null && mode == 1) {
            longPressTriggered = true
            dragOriginX = downX
            dragOriginTimeMs = touched!!.startTimeMs
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    fun select(id: String?) { selectedId = id; invalidate() }

    private fun hit(x: Float): EffectClip? = clips.lastOrNull {
        x >= controller.timeToX(it.startTimeMs) && x <= controller.timeToX(it.endTimeMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        clips.forEach { clip ->
            val left = controller.timeToX(clip.startTimeMs)
            val right = controller.timeToX(clip.endTimeMs)
            if (right < 0f || left > width) return@forEach
            val rect = RectF(left, 3f * density, right.coerceAtLeast(left + 8f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, fill)
            if (clip.id == selectedId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, border)
                val hw = 9f * density
                canvas.drawRoundRect(RectF(rect.left + 2f * density, rect.top + 4f * density, rect.left + hw, rect.bottom - 4f * density), 2f * density, 2f * density, handle)
                canvas.drawRoundRect(RectF(rect.right - hw, rect.top + 4f * density, rect.right - 2f * density, rect.bottom - 4f * density), 2f * density, 2f * density, handle)
            }
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(clip.effectName, rect.left + 8f * density, rect.centerY() + 3.5f * density, label)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handler.removeCallbacks(longPressRunnable)
                downX = event.x
                downY = event.rawY
                longPressTriggered = false
                lastX = event.x
                moved = false
                editStarted = false
                touched = hit(event.x)
                selectedId = touched?.id
                mode = 0
                touched?.let { clip ->
                    originStart = clip.startTimeMs
                    originDuration = clip.durationMs
                    val left = controller.timeToX(clip.startTimeMs)
                    val right = controller.timeToX(clip.endTimeMs)
                    val edge = 22f * density
                    mode = when {
                        event.x - left <= edge -> 2
                        right - event.x <= edge -> 3
                        else -> 1
                    }
                    if (mode == 1) handler.postDelayed(longPressRunnable, 500L)
                }
                onSelected(touched)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressTriggered && mode == 1 &&
                    (abs(event.x - downX) > 8f * density || abs(event.rawY - downY) > 8f * density)
                ) handler.removeCallbacks(longPressRunnable)
                val clip = touched
                if (longPressTriggered && clip != null && controller.pixelsPerMs > 0f) {
                    val delta = ((event.x - dragOriginX) / controller.pixelsPerMs).toLong()
                    clip.startTimeMs = (dragOriginTimeMs + delta).coerceAtLeast(0L)
                    onClipDrag(clip, event.rawY)
                    invalidate()
                    return true
                }
                if (clip != null && controller.pixelsPerMs > 0f) {
                    if (abs(event.x - downX) < 4f * density) {
                        lastX = event.x
                        return true
                    }
                    if (!editStarted) { onEditStarted(); editStarted = true }
                    val delta = ((event.x - downX) / controller.pixelsPerMs).toLong()
                    when (mode) {
                        2 -> {
                            val end = originStart + originDuration
                            val start = (originStart + delta).coerceIn(0L, end - 100L)
                            clip.startTimeMs = start
                            clip.durationMs = end - start
                        }
                        3 -> clip.durationMs = (originDuration + delta).coerceAtLeast(100L)
                        else -> clip.startTimeMs = (originStart + delta).coerceAtLeast(0L)
                    }
                    moved = true
                    invalidate()
                } else if (clip == null) {
                    val dx = event.x - lastX
                    if (abs(event.x - downX) > 5f * density) {
                        controller.offsetPx -= dx
                        controller.clampOffset()
                        val position = controller.xToTime(width / 2f)
                        controller.positionMs = position
                        onTimelineScrolled(position)
                        moved = true
                        invalidate()
                    }
                }
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) touched?.let { onClipDropped(it, event.rawY) }
                if (moved && editStarted) onChanged()
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                mode = 0
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                mode = 0
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
