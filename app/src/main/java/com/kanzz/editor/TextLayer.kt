package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.animation.AnimatorListenerAdapter
import android.widget.Toast
import kotlin.math.abs

data class TextClip(
    val id: String = java.util.UUID.randomUUID().toString(),
    var text: String,
    var startTimeMs: Long,
    var durationMs: Long = 2000L,
    var fontName: String = "Default",
    var trackIndex: Int = 0,
    var positionX: Float = 0f,
    var positionY: Float = 0f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var color: Int = Color.WHITE,
    var opacity: Float = 1f,
    var bold: Boolean = true
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

class TextClipManager {
    val clips = mutableListOf<TextClip>()

    // Pilih layer pertama yang kosong pada rentang waktu clip baru.
    // Clip yang berakhir sebelum playhead tidak menghalangi clip baru.
    fun firstAvailableLane(startTimeMs: Long, durationMs: Long): Int {
        val endTimeMs = startTimeMs + durationMs
        var lane = 0
        while (clips.any { existing ->
                existing.trackIndex == lane &&
                    startTimeMs < existing.endTimeMs &&
                    endTimeMs > existing.startTimeMs
            }) lane++
        return lane
    }

    fun add(clip: TextClip) {
        clip.trackIndex = firstAvailableLane(clip.startTimeMs, clip.durationMs)
        clips.add(clip)
        sort()
    }

    fun find(id: String?) = clips.firstOrNull { it.id == id }
    fun remove(id: String?) { clips.removeAll { it.id == id } }

    fun canMoveToLane(clip: TextClip, lane: Int): Boolean =
        clips.none { other ->
            other.id != clip.id && other.trackIndex == lane &&
                clip.startTimeMs < other.endTimeMs && clip.endTimeMs > other.startTimeMs
        }

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

    // Rapikan nomor layer setelah clip dipindahkan; layer kosong dihapus.
    fun compactLanes() {
        val laneOrder = clips.map { it.trackIndex }.distinct().sorted()
        val remap = laneOrder.mapIndexed { index, oldLane -> oldLane to index }.toMap()
        clips.forEach { clip -> clip.trackIndex = remap[clip.trackIndex] ?: 0 }
        sort()
    }

    private fun sort() {
        clips.sortWith(compareBy<TextClip> { it.trackIndex }.thenBy { it.startTimeMs })
    }
}

class TextTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val laneIndex: Int,
    private val allClips: () -> List<TextClip>,
    private val onSelected: (TextClip?) -> Unit,
    private val onChanged: () -> Unit,
    private val onTimelineScrolled: (Long) -> Unit,
    private val onClipDropped: (TextClip, Float) -> Unit,
    private val onClipDrag: (TextClip, Float) -> Unit
) : View(context) {
    private val clips: List<TextClip> get() = allClips().filter { it.trackIndex == laneIndex }
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5674E.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101010.toInt(); textSize = 10f * density; isFakeBoldText = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt(); strokeWidth = 1.5f * density
    }

    private var selectedId: String? = null
    private var touched: TextClip? = null
    private var mode = 0 // 1 move, 2 trim start, 3 trim end
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var dragOriginX = 0f
    private var dragOriginTimeMs = 0L
    private var originStart = 0L
    private var originDuration = 0L
    private var longPressTriggered = false
    private var isScrollingTimeline = false
    private var snapGuideTimeMs: Long? = null
    private var panVelocityTracker: VelocityTracker? = null
    private var panFlingAnimator: ValueAnimator? = null

    private fun applyTimelinePan(deltaX: Float) {
        if (deltaX == 0f) return
        controller.offsetPx -= deltaX
        controller.clampOffset()
        val position = controller.xToTime(width / 2f)
        controller.positionMs = position
        onTimelineScrolled(position)
        invalidate()
    }

    private fun startTimelineFling(velocityX: Float) {
        val velocity = velocityX.coerceIn(-5000f, 5000f)
        if (abs(velocity) < 350f) return
        val duration = (abs(velocity) * 0.16f).toInt().coerceIn(220, 850)
        val distance = velocity * duration / 2000f
        var previous = 0f
        panFlingAnimator?.cancel()
        panFlingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration.toLong()
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                applyTimelinePan(distance * (fraction - previous))
                previous = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (panFlingAnimator === this@apply) panFlingAnimator = null
                }
            })
            start()
        }
    }

    private val longPressRunnable = Runnable {
        touched?.let { clip ->
            longPressTriggered = true
            dragOriginX = downX
            dragOriginTimeMs = clip.startTimeMs
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    }

    fun select(id: String?) { selectedId = id; invalidate() }

    private fun hit(x: Float): TextClip? = clips.lastOrNull {
        x >= controller.timeToX(it.startTimeMs) && x <= controller.timeToX(it.endTimeMs)
    }

    private fun hasCollision(clip: TextClip, proposedStartMs: Long): Boolean {
        val end = proposedStartMs + clip.durationMs
        return clips.any { other ->
            other.id != clip.id && proposedStartMs < other.endTimeMs && end > other.startTimeMs
        }
    }

    private fun snapTime(clip: TextClip, proposedStartMs: Long): SnapResult {
        val state = TimelineState(
            clips = clips.map { item ->
                EditorClip(
                    id = item.id, mediaUri = "", trackIndex = 0,
                    startTime = item.startTimeMs, duration = item.durationMs,
                    sourceDuration = item.durationMs
                )
            },
            playheadMs = controller.positionMs
        )
        return TimelineSnapEngine.snap(
            proposedTimeMs = proposedStartMs,
            movingClipId = clip.id,
            state = state,
            pixelsPerMs = controller.pixelsPerMs,
            tolerancePx = 10f * density
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        clips.forEach { clip ->
            val left = controller.timeToX(clip.startTimeMs)
            val right = controller.timeToX(clip.endTimeMs)
            if (right < 0f || left > width) return@forEach
            val rect = RectF(left, 3f * density, right.coerceAtLeast(left + 8f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, clipPaint)
            if (clip.id == selectedId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, outlinePaint)
                val handleW = 11f * density
                val handleTop = rect.top + 4f * density
                val handleBottom = rect.bottom - 4f * density
                canvas.drawRoundRect(RectF(rect.left + 2f * density, handleTop, rect.left + handleW, handleBottom), 2f * density, 2f * density, handlePaint)
                canvas.drawRoundRect(RectF(rect.right - handleW, handleTop, rect.right - 2f * density, handleBottom), 2f * density, 2f * density, handlePaint)
            }
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(clip.text.replace('\n', ' '), rect.left + 8f * density, rect.centerY() + 3.5f * density, textPaint)
            canvas.restore()
        }
        snapGuideTimeMs?.let { time ->
            val x = controller.timeToX(time)
            if (x in 0f..width.toFloat()) canvas.drawLine(x, 0f, x, height.toFloat(), snapGuidePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panFlingAnimator?.cancel()
                panFlingAnimator = null
                panVelocityTracker?.recycle()
                panVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                touched = hit(event.x)
                longPressTriggered = false
                isScrollingTimeline = false
                snapGuideTimeMs = null
                touched?.let { clip ->
                    selectedId = clip.id
                    originStart = clip.startTimeMs
                    originDuration = clip.durationMs
                    val left = controller.timeToX(clip.startTimeMs)
                    val right = controller.timeToX(clip.endTimeMs)
                    val edge = 26f * density
                    mode = when {
                        event.x - left <= edge -> 2
                        right - event.x <= edge -> 3
                        else -> 1
                    }
                    if (mode == 1) handler.postDelayed(longPressRunnable, 500L)
                } ?: run {
                    selectedId = null
                    mode = 0
                }
                onSelected(touched)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                panVelocityTracker?.addMovement(event)
                if (!longPressTriggered && mode != 2 && mode != 3) {
                    val dx = event.x - downX
                    if (abs(dx) > 8f * density) handler.removeCallbacks(longPressRunnable)
                    if (touched == null && abs(dx) > 8f * density) {
                        isScrollingTimeline = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isScrollingTimeline) {
                        val delta = event.x - lastTouchX
                        applyTimelinePan(delta)
                        lastTouchX = event.x
                    }
                    return true
                }

                val clip = touched ?: return true
                if (controller.pixelsPerMs <= 0f) return true
                if (mode == 2 || mode == 3) {
                    val deltaMs = ((event.x - downX) / controller.pixelsPerMs).toLong()
                    if (mode == 2) {
                        val fixedEnd = originStart + originDuration
                        val proposedStart = (originStart + deltaMs).coerceAtLeast(0L)
                            .coerceAtMost(fixedEnd - 250L)
                        val proposedDuration = fixedEnd - proposedStart
                        val overlaps = clips.any { other ->
                            other.id != clip.id && proposedStart < other.endTimeMs &&
                                fixedEnd > other.startTimeMs
                        }
                        if (!overlaps) {
                            clip.startTimeMs = proposedStart
                            clip.durationMs = proposedDuration
                        }
                    } else {
                        val proposedDuration = (originDuration + deltaMs).coerceAtLeast(250L)
                        val proposedEnd = originStart + proposedDuration
                        val overlaps = clips.any { other ->
                            other.id != clip.id && originStart < other.endTimeMs &&
                                proposedEnd > other.startTimeMs
                        }
                        if (!overlaps) clip.durationMs = proposedDuration
                    }
                    onChanged()
                    invalidate()
                    return true
                }
                val deltaMs = ((event.x - dragOriginX) / controller.pixelsPerMs).toLong()
                val rawStart = (dragOriginTimeMs + deltaMs).coerceAtLeast(0L)
                val snap = snapTime(clip, rawStart)
                val snappedStart = snap.timeMs.coerceAtLeast(0L)
                val useSnap = snap.snapped && !hasCollision(clip, snappedStart)
                val proposed = if (useSnap) snappedStart else rawStart
                if (!hasCollision(clip, proposed)) {
                    clip.startTimeMs = proposed
                    snapGuideTimeMs = if (useSnap) snap.targetTimeMs else null
                    onChanged()
                    onClipDrag(clip, event.rawY)
                } else {
                    snapGuideTimeMs = null
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                panVelocityTracker?.addMovement(event)
                handler.removeCallbacks(longPressRunnable)
                if (isScrollingTimeline) {
                    panVelocityTracker?.computeCurrentVelocity(1000)
                    startTimelineFling(panVelocityTracker?.xVelocity ?: 0f)
                }
                panVelocityTracker?.recycle()
                panVelocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressTriggered) {
                    touched?.let { onClipDropped(it, event.rawY) }
                } else if (!isScrollingTimeline && touched != null) {
                    // Perubahan trim sudah diterapkan langsung selama jari bergerak.
                    if (mode == 2 || mode == 3) onChanged()
                }
                isScrollingTimeline = false
                longPressTriggered = false
                touched = null
                snapGuideTimeMs = null
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                panFlingAnimator?.cancel()
                panFlingAnimator = null
                panVelocityTracker?.recycle()
                panVelocityTracker = null
                handler.removeCallbacks(longPressRunnable)
                isScrollingTimeline = false
                longPressTriggered = false
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                snapGuideTimeMs = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
