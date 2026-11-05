package com.penflow.tablet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import com.penflow.tablet.model.AspectRatioMode
import com.penflow.tablet.model.OsuAreaPreset
import com.penflow.tablet.model.PacketConstants
import com.penflow.tablet.model.PollingRatePreset
import com.penflow.tablet.model.TabletSettings
import com.penflow.tablet.network.UsbSocketManager
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class StylusCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var socketManager: UsbSocketManager? = null
    var settings: TabletSettings = TabletSettings()
        set(value) {
            if (field == value) return
            field = value
            recalculateActiveArea()
            invalidate()
        }

    // Callback for live telemetry display in UI
    var onStylusTelemetry: ((pressure: Float, tiltX: Short, tiltY: Short, tool: Byte, hover: Boolean) -> Unit)? = null

    // Callback to restore HUD when user double-taps top-left corner with finger
    var isHudVisibleState: Boolean = true
    var onRequestShowHud: (() -> Unit)? = null
    private var lastCornerFingerTapMs = 0L

    // Calibration mode: allows freely dragging the active area box across the screen
    var isCalibratingPosition: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var onAreaPositionChanged: ((offsetXPercent: Float, offsetYPercent: Float) -> Unit)? = null

    // Drawing area calculation for aspect ratio matching
    private val activeRect = RectF()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(80, 0, 229, 255)
        strokeWidth = 3f
    }
    private val inactivePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 10, 12, 16)
    }
    private val areaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        textSize = 28f
        isFakeBoldText = true
    }
    private val centerMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 0, 229, 255)
        strokeWidth = 2f
    }

    // Local ink trail / cursor visualization
    private val localTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(220, 0, 229, 255)
        strokeWidth = 6f
    }
    private val hoverCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
    }
    private val trailPath = Path()
    private val trailPoints = ArrayDeque<PointF>()
    private val MAX_TRAIL_POINTS = 32

    // High polling rate tracking (500Hz / 1000Hz sub-step micro-interpolation)
    private var lastProcessedX = -1f
    private var lastProcessedY = -1f
    private var lastProcessedPressure = 0f

    private var hoverX = -1f
    private var hoverY = -1f
    private var isHovering = false
    private var touchX = -1f
    private var touchY = -1f
    private var isTouching = false
    private var isStrokeInsideArea = false
    private var isHoverInsideArea = false

    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 180, 200, 220)
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val touchPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 0, 229, 255)
    }

    // Hardware palm rejection & telemetry throttling
    private var lastStylusTime = 0L
    private var hasDetectedActiveStylus = false
    private var activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
    private val ACTIVE_STYLUS_AUTO_PALM_REJECT_MS = 10_000L
    private var lastTelemetryUpdateMs = 0L
    private var lastTelemetryAction: Byte = -1
    private var lastTelemetryTool: Byte = -1

    // Cached display metrics to avoid repeated JNI lookups on every 240Hz motion event
    private var cachedWidthMm = 293.76f
    private var cachedHeightMm = 183.60f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Ensure this view receives hover events
        isHovered = true

        // Request unbuffered hardware dispatch on Android 11+ to bypass VSync input batching delay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestUnbufferedDispatch(
                InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_MOUSE
            )
        }

        // Disable Android 14+ stylus handwriting probing so InputMethodManager never interrupts stylus strokes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            isAutoHandwritingEnabled = false
        }
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (!settings.showLocalInkTrail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateActiveArea()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            val maxStripH = (180f * resources.displayMetrics.density).toInt().coerceAtLeast(100)
            val rects = mutableListOf<Rect>()
            var y = 0
            while (y < h) {
                val bottom = (y + maxStripH).coerceAtMost(h)
                rects.add(Rect(0, y, w, bottom))
                y += maxStripH
            }
            systemGestureExclusionRects = rects
        }
    }

    private fun addTrailPoint(x: Float, y: Float) {
        trailPoints.addLast(PointF(x, y))
        while (trailPoints.size > MAX_TRAIL_POINTS) {
            trailPoints.removeFirst()
        }
    }

    private fun resetTrail() {
        trailPoints.clear()
        trailPath.rewind()
    }

    private fun emitPointWithInterpolation(
        action: Byte,
        rawX: Float,
        rawY: Float,
        rawPressure: Float,
        rawTilt: Float,
        rawOrientation: Float,
        toolType: Byte,
        buttonState: Byte
    ) {
        // Direct zero-latency dispatch of the true hardware coordinate without synthetic trailing interpolation
        processPointerPoint(
            action = action,
            rawX = rawX,
            rawY = rawY,
            rawPressure = rawPressure,
            rawTilt = rawTilt,
            rawOrientation = rawOrientation,
            toolType = toolType,
            buttonState = buttonState
        )

        if (action == PacketConstants.ACTION_CANCEL || (action == PacketConstants.ACTION_UP && toolType == PacketConstants.TOOL_FINGER)) {
            lastProcessedX = -1f
            lastProcessedY = -1f
            lastProcessedPressure = 0f
        } else {
            lastProcessedX = rawX
            lastProcessedY = rawY
            lastProcessedPressure = rawPressure
        }
    }

    private fun recalculateActiveArea() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val dm = resources.displayMetrics
        val xdpi = if (dm.xdpi > 30f) dm.xdpi else dm.densityDpi.toFloat()
        val ydpi = if (dm.ydpi > 30f) dm.ydpi else dm.densityDpi.toFloat()
        val pxPerMmX = xdpi / 25.4f
        val pxPerMmY = ydpi / 25.4f

        val area = settings.tabletArea

        if (area.isFullScreen || area.preset == OsuAreaPreset.FULL) {
            // True 100% edge-to-edge full tablet screen area
            activeRect.set(0f, 0f, w, h)
        } else {
            // True physical millimeter mapping (osu! / Custom active area)
            val maxWm = w / pxPerMmX
            val maxHm = h / pxPerMmY

            val targetWm = area.widthMm.coerceIn(15f, maxWm)
            val targetHm = area.heightMm.coerceIn(10f, maxHm)

            val activeWidthPx = (targetWm * pxPerMmX).coerceIn(40f, w)
            val activeHeightPx = (targetHm * pxPerMmY).coerceIn(40f, h)

            val maxOffsetX = (w - activeWidthPx).coerceAtLeast(0f)
            val maxOffsetY = (h - activeHeightPx).coerceAtLeast(0f)

            val finalLeft = maxOffsetX * area.offsetXPercent.coerceIn(0f, 1f)
            val finalTop = maxOffsetY * area.offsetYPercent.coerceIn(0f, 1f)

            activeRect.set(finalLeft, finalTop, finalLeft + activeWidthPx, finalTop + activeHeightPx)
        }

        cachedWidthMm = activeRect.width() / pxPerMmX
        cachedHeightMm = activeRect.height() / pxPerMmY
    }

    private fun findStylusPointerIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                return i
            }
        }
        return -1
    }

    private fun isPointInOrNearActiveArea(x: Float, y: Float): Boolean {
        val margin = 12f * resources.displayMetrics.density
        return x >= (activeRect.left - margin) &&
               x <= (activeRect.right + margin) &&
               y >= (activeRect.top - margin) &&
               y <= (activeRect.bottom + margin)
    }

    private fun findBestTouchPointerIndex(event: MotionEvent): Int {
        // 1. If we already locked onto the capacitive pen's pointerId, stay locked onto it!
        if (activeTouchPointerId != MotionEvent.INVALID_POINTER_ID) {
            val existingIdx = event.findPointerIndex(activeTouchPointerId)
            if (existingIdx >= 0) {
                return existingIdx
            }
            activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        }

        // 2. Otherwise, find the best pointer inside/near activeRect (preferring smallest contact area = pen tip over palm)
        var bestIdx = -1
        var smallestMajor = Float.MAX_VALUE
        val liftingIdx = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

        for (i in 0 until event.pointerCount) {
            if (i == liftingIdx) continue
            val px = event.getX(i)
            val py = event.getY(i)
            if (isPointInOrNearActiveArea(px, py)) {
                val major = event.getTouchMajor(i)
                if (bestIdx == -1 || major < smallestMajor) {
                    smallestMajor = major
                    bestIdx = i
                }
            }
        }

        if (bestIdx >= 0) {
            activeTouchPointerId = event.getPointerId(bestIdx)
            return bestIdx
        }

        return 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isCalibratingPosition) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val activeW = activeRect.width()
                    val activeH = activeRect.height()
                    val maxOffsetX = (w - activeW).coerceAtLeast(1f)
                    val maxOffsetY = (h - activeH).coerceAtLeast(1f)

                    val targetLeft = event.x - (activeW / 2f)
                    val targetTop = event.y - (activeH / 2f)

                    val newOffsetX = (targetLeft / maxOffsetX).coerceIn(0f, 1f)
                    val newOffsetY = (targetTop / maxOffsetY).coerceIn(0f, 1f)

                    onAreaPositionChanged?.invoke(newOffsetX, newOffsetY)
                    return true
                }
            }
            return true
        }

        val nowUptime = SystemClock.uptimeMillis()

        // 1. Check for active hardware stylus (S-Pen / Lenovo Pen) across all pointers
        val stylusIdx = findStylusPointerIndex(event)

        if (stylusIdx < 0) {
            // Capacitive / Touch Pen or Finger (TOOL_TYPE_FINGER):
            // Check if user is double-tapping top-left corner to restore hidden HUD:
            if (!isHudVisibleState && event.actionMasked == MotionEvent.ACTION_DOWN) {
                val cornerZonePx = 96f * resources.displayMetrics.density
                if (event.x <= cornerZonePx && event.y <= cornerZonePx) {
                    if (nowUptime - lastCornerFingerTapMs in 40L..420L) {
                        lastCornerFingerTapMs = 0L
                        onRequestShowHud?.invoke()
                        return true
                    }
                    lastCornerFingerTapMs = nowUptime
                }
            }

            // Automatically reject resting palm (TOOL_TYPE_FINGER) whenever an active hardware stylus was used recently
            // OR when strictPalmRejection is enabled. This guarantees a resting palm NEVER freezes or steals cursor
            // during stylus hover/gameplay, while still allowing capacitive pens on phones or when stylus is set aside!
            if (hasDetectedActiveStylus &&
                (settings.strictPalmRejection || (nowUptime - lastStylusTime < ACTIVE_STYLUS_AUTO_PALM_REJECT_MS))
            ) {
                return true
            }
        }

        val pIdx = if (stylusIdx >= 0) stylusIdx else findBestTouchPointerIndex(event)
        val toolType = event.getToolType(pIdx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

        if (isStylus) {
            lastStylusTime = nowUptime
            hasDetectedActiveStylus = true
        }
        isHovering = false

        val wireTool = when (toolType) {
            MotionEvent.TOOL_TYPE_ERASER -> PacketConstants.TOOL_ERASER
            MotionEvent.TOOL_TYPE_STYLUS -> PacketConstants.TOOL_STYLUS
            else -> PacketConstants.TOOL_FINGER
        }

        val rawX = event.getX(pIdx)
        val rawY = event.getY(pIdx)
        val rawPressureVal = event.getPressure(pIdx)

        // Provide 1.0f pressure for capacitive touch pen / finger touches so clicks register cleanly
        val basePressure = if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            if (rawPressureVal > 0.05f) rawPressureVal else 1.0f
        } else {
            rawPressureVal
        }

        val buttonState = extractButtonFlags(event.buttonState)
        val isPointInside = isPointInOrNearActiveArea(rawX, rawY)

        // Resolve effective action specifically for our tracked pointer index (pIdx)
        val effectiveAction = when (val masked = event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_POINTER_DOWN -> if (event.actionIndex == pIdx) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == pIdx) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            else -> masked
        }

        when (effectiveAction) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!isPointInside && !isStylus) {
                    // Touched far outside active area (e.g. palm on bezel): ignore
                    if (activeTouchPointerId == event.getPointerId(pIdx)) {
                        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    isStrokeInsideArea = false
                    isTouching = false
                    return true
                }

                val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)

                lastProcessedX = clampedX
                lastProcessedY = clampedY
                lastProcessedPressure = basePressure

                isStrokeInsideArea = true
                isTouching = true
                touchX = clampedX
                touchY = clampedY

                emitPointWithInterpolation(
                    action = PacketConstants.ACTION_DOWN,
                    rawX = clampedX,
                    rawY = clampedY,
                    rawPressure = basePressure,
                    rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                    rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                    toolType = wireTool,
                    buttonState = buttonState
                )
                if (settings.showLocalInkTrail) {
                    resetTrail()
                    addTrailPoint(clampedX, clampedY)
                    postInvalidateOnAnimation()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isStrokeInsideArea) {
                    if (isPointInside || isStylus) {
                        val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                        val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)
                        lastProcessedX = clampedX
                        lastProcessedY = clampedY
                        lastProcessedPressure = basePressure
                        isStrokeInsideArea = true
                        isTouching = true
                        touchX = clampedX
                        touchY = clampedY

                        emitPointWithInterpolation(
                            action = PacketConstants.ACTION_DOWN,
                            rawX = clampedX,
                            rawY = clampedY,
                            rawPressure = basePressure,
                            rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                            rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                            toolType = wireTool,
                            buttonState = buttonState
                        )
                        if (settings.showLocalInkTrail) {
                            resetTrail()
                            addTrailPoint(clampedX, clampedY)
                            postInvalidateOnAnimation()
                        }
                    }
                } else {
                    val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                    val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)
                    isTouching = true
                    touchX = clampedX
                    touchY = clampedY

                    // Dispatch the latest hardware coordinate FIRST for zero-latency response
                    emitPointWithInterpolation(
                        action = PacketConstants.ACTION_MOVE,
                        rawX = clampedX,
                        rawY = clampedY,
                        rawPressure = basePressure,
                        rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                        rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                        toolType = wireTool,
                        buttonState = buttonState
                    )

                    if (settings.showLocalInkTrail) {
                        val historySize = event.historySize
                        for (h in 0 until historySize) {
                            val hX = event.getHistoricalX(pIdx, h).coerceIn(activeRect.left, activeRect.right)
                            val hY = event.getHistoricalY(pIdx, h).coerceIn(activeRect.top, activeRect.bottom)
                            addTrailPoint(hX, hY)
                        }
                        addTrailPoint(clampedX, clampedY)
                        postInvalidateOnAnimation()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
                if (isStrokeInsideArea) {
                    emitPointWithInterpolation(
                        action = PacketConstants.ACTION_UP,
                        rawX = rawX.coerceIn(activeRect.left, activeRect.right),
                        rawY = rawY.coerceIn(activeRect.top, activeRect.bottom),
                        rawPressure = 0f,
                        rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                        rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                        toolType = wireTool,
                        buttonState = buttonState
                    )
                    isStrokeInsideArea = false
                }
                lastProcessedX = -1f
                lastProcessedY = -1f
                lastProcessedPressure = 0f
                isTouching = false
                touchX = -1f
                touchY = -1f
                if (settings.showLocalInkTrail) {
                    resetTrail()
                    postInvalidateOnAnimation()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
                val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)
                if (isStrokeInsideArea || isStylus) {
                    emitPointWithInterpolation(
                        action = if (isStylus) PacketConstants.ACTION_HOVER else PacketConstants.ACTION_UP,
                        rawX = clampedX,
                        rawY = clampedY,
                        rawPressure = 0f,
                        rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                        rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                        toolType = wireTool,
                        buttonState = buttonState
                    )
                    isStrokeInsideArea = false
                }
                if (!isStylus) {
                    lastProcessedX = -1f
                    lastProcessedY = -1f
                    lastProcessedPressure = 0f
                }
                isTouching = false
                touchX = -1f
                touchY = -1f
                if (settings.showLocalInkTrail) {
                    resetTrail()
                    postInvalidateOnAnimation()
                }
            }
        }

        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Captures Hover Events from active stylus (hovering 1-2 cm above screen)
        val stylusIdx = findStylusPointerIndex(event)
        val pIdx = if (stylusIdx >= 0) stylusIdx else 0
        val toolType = event.getToolType(pIdx)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusTime = SystemClock.uptimeMillis()
            hasDetectedActiveStylus = true

            val wireTool = if (toolType == MotionEvent.TOOL_TYPE_ERASER) {
                PacketConstants.TOOL_ERASER
            } else {
                PacketConstants.TOOL_STYLUS
            }

            val buttonState = extractButtonFlags(event.buttonState)
            val rawX = event.getX(pIdx)
            val rawY = event.getY(pIdx)

            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                        requestUnbufferedDispatch(event)
                    }

                    val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                    val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)
                    isHovering = true
                    isHoverInsideArea = true
                    hoverX = clampedX
                    hoverY = clampedY

                    // Dispatch the latest hardware hover position immediately without historical queue buildup
                    emitPointWithInterpolation(
                        action = PacketConstants.ACTION_HOVER,
                        rawX = clampedX,
                        rawY = clampedY,
                        rawPressure = 0f,
                        rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                        rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                        toolType = wireTool,
                        buttonState = buttonState
                    )

                    if (settings.showLocalInkTrail) {
                        postInvalidateOnAnimation()
                    }
                    return true
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    // Send the exit coordinate as a hover update so if ZUI palm policy briefly fires HOVER_EXIT,
                    // the cursor continues smoothly to the latest position without freezing
                    val clampedX = rawX.coerceIn(activeRect.left, activeRect.right)
                    val clampedY = rawY.coerceIn(activeRect.top, activeRect.bottom)
                    emitPointWithInterpolation(
                        action = PacketConstants.ACTION_HOVER,
                        rawX = clampedX,
                        rawY = clampedY,
                        rawPressure = 0f,
                        rawTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                        rawOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                        toolType = wireTool,
                        buttonState = buttonState
                    )
                    isHoverInsideArea = false
                    isHovering = false
                    hoverX = -1f
                    hoverY = -1f
                    if (settings.showLocalInkTrail) {
                        postInvalidateOnAnimation()
                    }
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun processPointerPoint(
        action: Byte,
        rawX: Float,
        rawY: Float,
        rawPressure: Float,
        rawTilt: Float,
        rawOrientation: Float,
        toolType: Byte,
        buttonState: Byte
    ) {
        // Map raw canvas coordinates into normalized (0.00000f .. 1.00000f) active area
        val activeW = activeRect.width()
        val activeH = activeRect.height()
        if (activeW <= 0 || activeH <= 0) return

        val normX = ((rawX - activeRect.left) / activeW).coerceIn(0f, 1f)
        val normY = ((rawY - activeRect.top) / activeH).coerceIn(0f, 1f)

        // Apply calibrated pressure curve
        val calibratedPressure = settings.applyPressureCurve(rawPressure)

        // Convert Android tilt/orientation into standard Windows Tilt (-90 to +90 degrees)
        val (tiltX, tiltY) = calculateWindowsTilt(rawTilt, rawOrientation)

        val isFullScreenMode = settings.tabletArea.isFullScreen || settings.tabletArea.preset == OsuAreaPreset.FULL
        val finalButtons = if (isFullScreenMode) {
            (buttonState.toInt() or PacketConstants.BUTTON_FLAG_FULLSCREEN.toInt()).toByte()
        } else {
            buttonState
        }

        // Transmit via low-latency USB socket FIRST before any UI work
        socketManager?.sendPointerEvent(
            action = action,
            normX = normX,
            normY = normY,
            pressure = calibratedPressure,
            tiltX = tiltX,
            tiltY = tiltY,
            toolType = toolType,
            buttonState = finalButtons,
            activeWidthMm = cachedWidthMm,
            activeHeightMm = cachedHeightMm
        )

        // Only notify Compose UI when the active tool type actually changes (e.g. Pen <-> Eraser)
        // so Compose NEVER recomposes during stylus movement!
        if (toolType != lastTelemetryTool) {
            lastTelemetryTool = toolType
            onStylusTelemetry?.invoke(calibratedPressure, tiltX, tiltY, toolType, action == PacketConstants.ACTION_HOVER)
        }
    }

    /**
     * Converts Android MotionEvent AXIS_TILT and AXIS_ORIENTATION into Windows Ink TiltX and TiltY:
     * Android AXIS_TILT is radians from normal [0, PI/2].
     * Android AXIS_ORIENTATION is radians from +Y axis [-PI, +PI].
     * Windows TiltX is angle relative to Y-Z plane (-90 to +90 deg).
     * Windows TiltY is angle relative to X-Z plane (-90 to +90 deg).
     */
    private fun calculateWindowsTilt(tiltRad: Float, orientRad: Float): Pair<Short, Short> {
        if (tiltRad <= 0.001f) return Pair(0.toShort(), 0.toShort())

        val sinTilt = sin(tiltRad.toDouble())
        val cosTilt = cos(tiltRad.toDouble())

        // Projection vectors
        val x = sinTilt * sin(orientRad.toDouble())
        val y = -sinTilt * cos(orientRad.toDouble())
        val z = cosTilt

        val degX = Math.toDegrees(atan2(x, z)).roundToInt().coerceIn(-90, 90).toShort()
        val degY = Math.toDegrees(atan2(y, z)).roundToInt().coerceIn(-90, 90).toShort()

        return Pair(degX, degY)
    }

    private fun extractButtonFlags(buttonState: Int): Byte {
        var flags: Byte = PacketConstants.BUTTON_NONE
        val primary = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val secondary = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

        if (settings.invertStylusButton) {
            if (secondary) flags = (flags.toInt() or PacketConstants.BUTTON_BARREL_PRIMARY.toInt()).toByte()
            if (primary) flags = (flags.toInt() or PacketConstants.BUTTON_SECONDARY.toInt()).toByte()
        } else {
            if (primary) flags = (flags.toInt() or PacketConstants.BUTTON_BARREL_PRIMARY.toInt()).toByte()
            if (secondary) flags = (flags.toInt() or PacketConstants.BUTTON_SECONDARY.toInt()).toByte()
        }
        return flags
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw area guides (borders, dimming, and corner markers)
        if (settings.showAreaGuides) {
            val isCroppedArea = activeRect.width() < (w - 10f) || activeRect.height() < (h - 10f)
            if (isCroppedArea) {
                // Dim inactive regions outside active area
                if (activeRect.top > 0) canvas.drawRect(0f, 0f, w, activeRect.top, inactivePaint)
                if (activeRect.bottom < h) canvas.drawRect(0f, activeRect.bottom, w, h, inactivePaint)
                if (activeRect.left > 0) canvas.drawRect(0f, activeRect.top, activeRect.left, activeRect.bottom, inactivePaint)
                if (activeRect.right < w) canvas.drawRect(activeRect.right, activeRect.top, w, activeRect.bottom, inactivePaint)

                // Draw bounding box
                canvas.drawRect(activeRect, borderPaint)

                // Area dimensions label in mm - placed OUTSIDE the active area box
                val dm = resources.displayMetrics
                val xdpi = if (dm.xdpi > 30f) dm.xdpi else dm.densityDpi.toFloat()
                val ydpi = if (dm.ydpi > 30f) dm.ydpi else dm.densityDpi.toFloat()
                val pxPerMmX = xdpi / 25.4f
                val pxPerMmY = ydpi / 25.4f
                val curWMm = activeRect.width() / pxPerMmX
                val curHMm = activeRect.height() / pxPerMmY
                val label = String.format(Locale.US, "ACTIVE AREA: %.1f x %.1f mm", curWMm, curHMm)

                // Position label OUTSIDE the active area: above the top border, or below bottom border if touching screen top
                val labelY = if (activeRect.top >= 36f) {
                    activeRect.top - 10f // ABOVE the top border (outside the box!)
                } else {
                    activeRect.bottom + 32f // BELOW the bottom border (outside the box!)
                }
                val labelX = activeRect.left.coerceAtLeast(16f)
                canvas.drawText(label, labelX, labelY, areaLabelPaint)
            } else {
                // Full Screen subtle guide border right at the physical edges of the display
                val inset = 1.5f
                canvas.drawRect(inset, inset, w - inset, h - inset, borderPaint)
                val dm = resources.displayMetrics
                val xdpi = if (dm.xdpi > 30f) dm.xdpi else dm.densityDpi.toFloat()
                val ydpi = if (dm.ydpi > 30f) dm.ydpi else dm.densityDpi.toFloat()
                val maxWMm = w / (xdpi / 25.4f)
                val maxHMm = h / (ydpi / 25.4f)
                val label = String.format(Locale.US, "FULL TABLET: %.1f x %.1f mm", maxWMm, maxHMm)
                canvas.drawText(label, 18f, 36f, areaLabelPaint)
            }

            // Draw center cross marker
            val cx = activeRect.centerX()
            val cy = activeRect.centerY()
            canvas.drawLine(cx - 24f, cy, cx + 24f, cy, centerMarkerPaint)
            canvas.drawLine(cx, cy - 24f, cx, cy + 24f, centerMarkerPaint)

            // When user is actively calibrating position, show instructional badge in the center
            if (isCalibratingPosition) {
                val calibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(230, 0, 229, 255)
                    textSize = 28f
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                canvas.drawText("DRAG TO POSITION", activeRect.centerX(), activeRect.centerY() + 9f, calibPaint)
            }
        }

        // Only draw touch circle, hover cursor ring, and local trail if explicitly enabled in Settings!
        // Keeping this off by default eliminates 100% of canvas redraws and SurfaceFlinger interrupts during osu! gameplay.
        if (settings.showLocalInkTrail) {
            if (trailPoints.size > 1) {
                trailPath.rewind()
                val first = trailPoints.first()
                trailPath.moveTo(first.x, first.y)
                for (pt in trailPoints) {
                    trailPath.lineTo(pt.x, pt.y)
                }
                canvas.drawPath(trailPath, localTrailPaint)
            }

            if (isHovering && hoverX >= 0 && hoverY >= 0) {
                canvas.drawCircle(hoverX, hoverY, 14f, hoverCursorPaint)
                canvas.drawCircle(hoverX, hoverY, 2f, hoverCursorPaint)
            }

            if (isTouching && touchX >= 0 && touchY >= 0) {
                canvas.drawCircle(touchX, touchY, 28f, touchPointPaint)
            }
        }
    }
}
