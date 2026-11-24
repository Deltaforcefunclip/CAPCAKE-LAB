package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.abs

class AudioTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val lane: AudioLane,
    private val onClipSelected: (AudioClip?) -> Unit,
    private val onClipMoved: () -> Unit,
    private val onClipTrimStart: (AudioClip) -> Unit,
    private val onClipTrimFinish: (AudioClip) -> Unit,
    private val onTimelineScrolled: (Long) -> Unit,
    private val onClipDropped: (AudioClip, Float) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt() }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF168B65.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 10f * density
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101010.toInt()
        textSize = 10f * density
        isFakeBoldText = true
        style = Paint.Style.FILL
    }
    private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt(); strokeWidth = 1.5f * density
    }

    private var selectedClipId: String? = null
    private var touchedClip: AudioClip? = null
    private var gestureMode = 0 // 1 move, 2 trim start, 3 trim end
    private var originStartMs = 0L
    private var originDurationMs = 0L
    private var originSourceStartMs = 0L
    private var trimUndoStarted = false
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragOriginX = 0f
    private var dragOriginTimeMs = 0L
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
    private var lastAppliedStartMs = Long.MIN_VALUE
    private val waveforms = mutableMapOf<String, FloatArray>()

    private val longPressRunnable = Runnable {
        touchedClip?.let { clip ->
            longPressTriggered = true
            dragOriginX = downX
            dragOriginTimeMs = clip.startTimeMs
            lastAppliedStartMs = clip.startTimeMs
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    }

    fun setWaveform(clipId: String, values: FloatArray) {
        waveforms[clipId] = values
        invalidate()
    }

    fun selectClip(clipId: String?) {
        selectedClipId = clipId
        invalidate()
    }

    private fun clipAt(x: Float): AudioClip? =
        lane.clips.lastOrNull {
            x >= controller.timeToX(it.startTimeMs) && x <= controller.timeToX(it.endTimeMs)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        lane.clips.forEach { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)
            if (endX < 0f || startX > width.toFloat()) return@forEach
            val rect = RectF(startX, 3f * density, endX.coerceAtLeast(startX + 4f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, clipPaint)

            val waveform = waveforms[clip.id]
            if (waveform != null && waveform.isNotEmpty()) {
                val centerY = rect.centerY()
                val halfHeight = (rect.height() * 0.40f).coerceAtLeast(2f * density)
                val step = (rect.width() / waveform.size).coerceAtLeast(1f)
                val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    strokeWidth = maxOf(1f * density, step * 0.55f)
                    strokeCap = Paint.Cap.ROUND
                }
                waveform.forEachIndexed { index, amplitude ->
                    val x = rect.left + index * step + step / 2f
                    if (x in rect.left..rect.right) {
                        val h = amplitude.coerceIn(0.04f, 1f) * halfHeight
                        canvas.drawLine(x, centerY, x, centerY - h, wavePaint)
                    }
                }
            }

            if (clip.id == selectedClipId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedPaint)
                val handleWidth = 10f * density
                val handleTop = rect.top + 4f * density
                val handleBottom = rect.bottom - 4f * density
                canvas.drawRoundRect(
                    RectF(rect.left + 2f * density, handleTop, rect.left + handleWidth, handleBottom),
                    2f * density, 2f * density, handlePaint
                )
                canvas.drawRoundRect(
                    RectF(rect.right - handleWidth, handleTop, rect.right - 2f * density, handleBottom),
                    2f * density, 2f * density, handlePaint
                )
            }
            canvas.save()
            canvas.clipRect(rect)
            val availableWidth = (rect.width() - 16f * density).coerceAtLeast(0f)
            var label = clip.name
            while (label.isNotEmpty() && textPaint.measureText(label) > availableWidth) {
                label = label.dropLast(1)
            }
            if (label.length < clip.name.length && label.isNotEmpty()) {
                while (label.isNotEmpty() && textPaint.measureText(label + "…") > availableWidth) {
                    label = label.dropLast(1)
                }
                label += "…"
            }
            if (label.isNotEmpty()) {
                val textX = startX + 8f * density
                val textY = height / 2f + 3.5f * density
                canvas.drawText(label, textX, textY, textStrokePaint)
                canvas.drawText(label, textX, textY, textPaint)
            }
            canvas.restore()
        }

        // Garis magnetik muncul saat awal/ujung audio tepat sejajar dengan target.
        snapGuideTimeMs?.let { timeMs ->
            val x = controller.timeToX(timeMs)
            if (x in 0f..width.toFloat()) {
                canvas.drawLine(x, 0f, x, height.toFloat(), snapGuidePaint)
            }
        }
    }

    private fun hasCollision(clip: AudioClip, proposedStartMs: Long): Boolean {
        val proposedEndMs = proposedStartMs + clip.durationMs
        return lane.clips.any { other ->
            other.id != clip.id &&
                proposedStartMs < other.endTimeMs &&
                proposedEndMs > other.startTimeMs
        }
    }

    private fun snapTime(clip: AudioClip, proposedStartMs: Long): SnapResult {
        val snapState = TimelineState(
            clips = lane.clips.map { item ->
                EditorClip(
                    id = item.id,
                    mediaUri = "",
                    trackIndex = 0,
                    startTime = item.startTimeMs,
                    duration = item.durationMs,
                    sourceDuration = item.durationMs
                )
            },
            playheadMs = controller.positionMs
        )
        return TimelineSnapEngine.snap(
            proposedTimeMs = proposedStartMs,
            movingClipId = clip.id,
            state = snapState,
            pixelsPerMs = controller.pixelsPerMs,
            tolerancePx = 10f * density
        )
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
                longPressTriggered = false
                isScrollingTimeline = false
                snapGuideTimeMs = null
                touchedClip = clipAt(event.x)
                selectedClipId = touchedClip?.id
                gestureMode = 0
                trimUndoStarted = false
                touchedClip?.let { clip ->
                    originStartMs = clip.startTimeMs
                    originDurationMs = clip.durationMs
                    originSourceStartMs = clip.sourceStartMs
                    val left = controller.timeToX(clip.startTimeMs)
                    val right = controller.timeToX(clip.endTimeMs)
                    val edge = 26f * density
                    gestureMode = when {
                        event.x - left <= edge -> 2
                        right - event.x <= edge -> 3
                        else -> 1
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (gestureMode == 1) handler.postDelayed(longPressRunnable, 500L)
                }
                onClipSelected(touchedClip)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                panVelocityTracker?.addMovement(event)
                val trimClip = touchedClip
                if (trimClip != null && (gestureMode == 2 || gestureMode == 3)) {
                    if (controller.pixelsPerMs <= 0f) return true
                    val deltaMs = ((event.x - downX) / controller.pixelsPerMs).toLong()
                    if (deltaMs != 0L && !trimUndoStarted) {
                        trimUndoStarted = true
                        onClipTrimStart(trimClip)
                    }
                    if (gestureMode == 2) {
                        val minStart = (originStartMs - originSourceStartMs).coerceAtLeast(0L)
                        val proposedStart = (originStartMs + deltaMs)
                            .coerceIn(minStart, originStartMs + originDurationMs - 100L)
                        val proposedSourceStart = originSourceStartMs + (proposedStart - originStartMs)
                        val proposedDuration = originStartMs + originDurationMs - proposedStart
                        val overlaps = lane.clips.any { other ->
                            other.id != trimClip.id &&
                                proposedStart < other.endTimeMs &&
                                proposedStart + proposedDuration > other.startTimeMs
                        }
                        if (!overlaps) {
                            trimClip.startTimeMs = proposedStart
                            trimClip.sourceStartMs = proposedSourceStart
                            trimClip.durationMs = proposedDuration
                        }
                    } else {
                        val maxDuration = (trimClip.sourceDurationMs - originSourceStartMs)
                            .coerceAtLeast(100L)
                        val proposedDuration = (originDurationMs + deltaMs).coerceIn(100L, maxDuration)
                        val proposedEnd = originStartMs + proposedDuration
                        val overlaps = lane.clips.any { other ->
                            other.id != trimClip.id &&
                                originStartMs < other.endTimeMs &&
                                proposedEnd > other.startTimeMs
                        }
                        if (!overlaps) trimClip.durationMs = proposedDuration
                    }
                    invalidate()
                    return true
                }
                if (!longPressTriggered) {
                    val dxFromDown = event.x - downX
                    if (abs(dxFromDown) > 8f * density) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (!isScrollingTimeline && abs(dxFromDown) > 8f * density) {
                        isScrollingTimeline = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isScrollingTimeline) {
                        val dx = event.x - lastTouchX
                        applyTimelinePan(dx)
                        lastTouchX = event.x
                    }
                    return true
                }

                val clip = touchedClip ?: return true
                if (controller.pixelsPerMs <= 0f) return true

                // Posisi dihitung dari titik awal gesture, bukan akumulasi delta.
                // Ini mencegah gerakan tersendat setelah jari tertahan collision.
                val deltaMs = ((event.x - dragOriginX) / controller.pixelsPerMs).toLong()
                val rawProposed = (dragOriginTimeMs + deltaMs).coerceAtLeast(0L)
                val snapped = snapTime(clip, rawProposed)
                val snappedStart = snapped.timeMs.coerceAtLeast(0L)

                val useSnap = snapped.snapped && !hasCollision(clip, snappedStart)
                val finalStart = if (useSnap) snappedStart else rawProposed
                val finalStartSafe = if (!hasCollision(clip, finalStart)) {
                    finalStart
                } else {
                    // Tetap tahan di posisi valid terakhir saat bertemu klip lain.
                    clip.startTimeMs
                }

                snapGuideTimeMs = if (useSnap && finalStartSafe == snappedStart) {
                    snapped.targetTimeMs
                } else null

                if (finalStartSafe != clip.startTimeMs && !hasCollision(clip, finalStartSafe)) {
                    clip.startTimeMs = finalStartSafe
                    lastAppliedStartMs = finalStartSafe
                    onClipMoved()
                }
                lastTouchX = event.x
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
                    touchedClip?.let { onClipDropped(it, event.rawY) }
                }
                if (trimUndoStarted) {
                    touchedClip?.let { onClipTrimFinish(it) }
                }
                isScrollingTimeline = false
                longPressTriggered = false
                touchedClip = null
                gestureMode = 0
                trimUndoStarted = false
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
                touchedClip = null
                gestureMode = 0
                trimUndoStarted = false
                snapGuideTimeMs = null
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
