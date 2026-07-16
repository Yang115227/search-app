package com.smartsearch.app.feature.search.floatview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.smartsearch.app.core.utils.RectUtil

/**
 * 选题框悬浮窗 —— 全屏半透明遮罩 + 中间透明可拖动选区。
 *
 * # 视觉设计
 * - 全屏半透明黑色遮罩（alpha=0x80），中间矩形区域完全透明可透视底层页面
 * - 选区右上角绘制白色圆形 X 关闭按钮，半径 22dp
 * - 选区右下角绘制 L 形缩放控制点，边长 36dp
 * - 选区边框白色虚线，宽度 2dp
 *
 * # 触摸逻辑
 * | 触摸位置               | 行为                              |
 * |-----------------------|-----------------------------------|
 * | 遮罩区域（选区外）      | 点击关闭（取消选题）                |
 * | 选区内（非按钮区）      | 拖动整体选区                      |
 * | X 关闭按钮             | 点击关闭（取消选题）                |
 * | 右下角缩放控制点        | 拖动改变选区大小                   |
 *
 * # 窗口属性
 * - WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE：不拦截底层页面触控事件
 * - TYPE_APPLICATION_OVERLAY：Android 8+ 标准悬浮窗类型
 * - 全屏布局，选区坐标直接映射到屏幕坐标系
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，Canvas 绘制，无第三方依赖。
 */
class FloatSelectOverlay(private val context: Context) : View(context) {

    // ==================== 回调 ====================

    /** 点击关闭按钮时的回调 */
    var onDismiss: (() -> Unit)? = null

    /** 用户确认选区后的回调，参数为屏幕坐标系下的选区矩形 */
    var onRectConfirmed: ((Rect) -> Unit)? = null

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var isAttached = false

    // ==================== 尺寸常量（dp 转 px） ====================

    private val density = context.resources.displayMetrics.density
    private val dp: Float.(Float) -> Float = { value -> value * density }

    /** 遮罩颜色：半透明黑 */
    private val maskColor = 0x80000000.toInt()

    /** 选区边框颜色：白色 */
    private val borderColor = Color.WHITE

    /** 选区边框宽度 */
    private val borderWidth = 2f.dp(2f)

    /** X 关闭按钮半径 */
    private val closeButtonRadius = 22f.dp(22f)

    /** X 按钮线条长度 */
    private val closeCrossLength = 8f.dp(8f)

    /** X 按钮描边宽度 */
    private val closeButtonStroke = 2.5f.dp(2.5f)

    /** 缩放控制点总边长 */
    private val resizeHandleSize = 36f.dp(36f)

    /** 缩放控制点 L 形线条宽度 */
    private val resizeHandleStroke = 3f.dp(3f)

    /** 缩放控制点线条长度（两条 L 边各占边长的一半） */
    private val resizeHandleLineLen = 18f.dp(18f)

    /** 触摸热区扩大 */
    private val touchSlop = 12f.dp(12f)

    /** 选区最小尺寸 */
    private val minSelectionSize = 80f.dp(80f)

    // ==================== 选区矩形（屏幕坐标） ====================

    /** 当前选区矩形，初始化时居中，占屏幕宽高 40% */
    private var selectionRect = RectF()

    // ==================== 画笔 ====================

    /** 遮罩画笔（半透明黑） */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maskColor
        style = Paint.Style.FILL
    }

    /** 选区透明镂空画笔（CLEAR 模式） */
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    /** 选区边框画笔（白色实线） */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    /** 选区四角高亮短线画笔 */
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = borderWidth * 2f
        strokeCap = Paint.Cap.ROUND
    }

    /** X 关闭按钮背景圆画笔 */
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0) // 半透明黑底
        style = Paint.Style.FILL
    }

    /** X 关闭按钮线条画笔 */
    private val closeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = closeButtonStroke
        strokeCap = Paint.Cap.ROUND
    }

    /** 缩放控制点画笔 */
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resizeHandleStroke
        strokeCap = Paint.Cap.ROUND
    }

    // ==================== 触摸状态 ====================

    /** 当前触摸模式 */
    private var touchMode = TouchMode.NONE

    /** 触摸起始位置 */
    private var touchStartX = 0f
    private var touchStartY = 0f

    /** 拖拽开始时选区的原始位置 */
    private var dragStartRect = RectF()

    /** 缩放开始时选区的原始位置 */
    private var resizeStartRect = RectF()

    /** 缩放锚点（固定不变的左上角） */
    private var resizeAnchorX = 0f
    private var resizeAnchorY = 0f

    private enum class TouchMode {
        /** 未触摸 */
        NONE,
        /** 拖动选区整体 */
        DRAG,
        /** 拖动右下角缩放 */
        RESIZE
    }

    // ==================== 屏幕尺寸缓存 ====================

    private var screenWidth = 0
    private var screenHeight = 0

    // ==================== 初始化选区 ====================

    /**
     * 根据屏幕尺寸计算初始选区居中位置。
     */
    private fun initSelectionRect() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val selW = screenWidth * 0.44f
        val selH = screenHeight * 0.36f
        val left = (screenWidth - selW) / 2f
        val top = (screenHeight - selH) / 2f
        selectionRect.set(left, top, left + selW, top + selH)
    }

    // ==================== Canvas 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (selectionRect.isEmpty || selectionRect.width() <= 0 || selectionRect.height() <= 0) return

        // ── 第 1 层：绘制半透明遮罩 + 镂空选区 ──
        // 使用离屏 Bitmap 实现 CLEAR 镂空效果
        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // 先画满遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 在选区内镂空（CLEAR 模式）
        canvas.drawRect(selectionRect, clearPaint)

        canvas.restoreToCount(layerId)

        // ── 第 2 层：选区边框 ──
        canvas.drawRect(selectionRect, borderPaint)

        // ── 第 3 层：四角高亮短线 ──
        drawCornerHighlights(canvas)

        // ── 第 4 层：右上角 X 关闭按钮 ──
        drawCloseButton(canvas)

        // ── 第 5 层：右下角缩放控制点 ──
        drawResizeHandle(canvas)
    }

    /**
     * 绘制选区四角的高亮短线（相机取景框风格）。
     */
    private fun drawCornerHighlights(canvas: Canvas) {
        val cornerLen = 16f.dp(16f)
        val r = selectionRect

        // 左上角 ┐
        canvas.drawLine(r.left, r.top, r.left + cornerLen, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + cornerLen, cornerPaint)

        // 右上角 ┌
        canvas.drawLine(r.right - cornerLen, r.top, r.right, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + cornerLen, cornerPaint)

        // 左下角 ┘
        canvas.drawLine(r.left, r.bottom - cornerLen, r.left, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left + cornerLen, r.bottom, cornerPaint)

        // 右下角 └
        canvas.drawLine(r.right - cornerLen, r.bottom, r.right, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom - cornerLen, r.right, r.bottom, cornerPaint)
    }

    /**
     * 绘制右上角 X 关闭按钮。
     * 圆心位于选区右上角外侧右移半个半径，上移半个半径。
     */
    private fun drawCloseButton(canvas: Canvas) {
        val cx = selectionRect.right + closeButtonRadius * 0.3f
        val cy = selectionRect.top - closeButtonRadius * 0.3f

        // 背景圆
        canvas.drawCircle(cx, cy, closeButtonRadius, closeBgPaint)

        // X 线条
        val half = closeCrossLength
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, closeLinePaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, closeLinePaint)
    }

    /**
     * 绘制右下角 L 形缩放控制点。
     */
    private fun drawResizeHandle(canvas: Canvas) {
        val r = selectionRect
        val x = r.right - 4f.dp(4f)
        val y = r.bottom - 4f.dp(4f)
        val len = resizeHandleLineLen

        // L 形：右下角拐角，水平线向左 + 竖线向上
        // 水平线（从右下角向左）
        canvas.drawLine(x - len, y, x, y, resizePaint)
        // 竖线（从右下角向上）
        canvas.drawLine(x, y - len, x, y, resizePaint)
    }

    // ==================== 触摸事件处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> handleTouchMove(x, y)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handleTouchUp(x, y)
        }
        return true
    }

    /**
     * 手指按下：判断触摸位置，决定进入 DRAG / RESIZE / 点击关闭 模式。
     */
    private fun handleTouchDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y

        // 优先级 1：点击 X 关闭按钮
        if (RectUtil.hitCloseButton(x, y, selectionRect, closeButtonRadius * 2f, 0f, touchSlop)) {
            // 点击关闭 → 取消选题
            onDismiss?.invoke()
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 2：点击缩放控制点
        if (RectUtil.hitResizeHandle(x, y, selectionRect, resizeHandleSize, touchSlop)) {
            touchMode = TouchMode.RESIZE
            resizeStartRect.set(selectionRect)
            // 缩放锚点为左上角（固定不动）
            resizeAnchorX = selectionRect.left
            resizeAnchorY = selectionRect.top
            return
        }

        // 优先级 3：点击选区内部 → 拖动
        if (selectionRect.contains(x, y)) {
            touchMode = TouchMode.DRAG
            dragStartRect.set(selectionRect)
            return
        }

        // 优先级 4：点击遮罩区域（选区外）→ 取消选题
        onDismiss?.invoke()
        touchMode = TouchMode.NONE
    }

    /**
     * 手指移动：根据当前模式计算选区变化。
     */
    private fun handleTouchMove(x: Float, y: Float) {
        val dx = x - touchStartX
        val dy = y - touchStartY

        when (touchMode) {
            TouchMode.DRAG -> {
                // 整体平移
                val newLeft = dragStartRect.left + dx
                val newTop = dragStartRect.top + dy
                val newRight = dragStartRect.right + dx
                val newBottom = dragStartRect.bottom + dy

                selectionRect.set(newLeft, newTop, newRight, newBottom)
                // 约束在屏幕边界内
                clampToScreen()
                invalidate()
            }

            TouchMode.RESIZE -> {
                // 右下角缩放，左上角锚点固定
                var newRight = resizeStartRect.right + dx
                var newBottom = resizeStartRect.bottom + dy

                // 最小尺寸限制
                if (newRight - resizeAnchorX < minSelectionSize) {
                    newRight = resizeAnchorX + minSelectionSize
                }
                if (newBottom - resizeAnchorY < minSelectionSize) {
                    newBottom = resizeAnchorY + minSelectionSize
                }

                // 屏幕边界限制
                if (newRight > screenWidth) newRight = screenWidth.toFloat()
                if (newBottom > screenHeight) newBottom = screenHeight.toFloat()

                selectionRect.set(resizeAnchorX, resizeAnchorY, newRight, newBottom)
                invalidate()
            }

            TouchMode.NONE -> { /* 不处理 */ }
        }
    }

    /**
     * 手指抬起：
     * - DRAG 模式：确认选区，回调坐标
     * - RESIZE 模式：确认选区，回调坐标
     */
    private fun handleTouchUp(x: Float, y: Float) {
        when (touchMode) {
            TouchMode.DRAG, TouchMode.RESIZE -> {
                // 用户拖拽或缩放后抬起 → 确认选区
                val rect = Rect(
                    selectionRect.left.toInt(),
                    selectionRect.top.toInt(),
                    selectionRect.right.toInt(),
                    selectionRect.bottom.toInt()
                )
                onRectConfirmed?.invoke(rect)
            }
            TouchMode.NONE -> { /* 已处理 */ }
        }
        touchMode = TouchMode.NONE
    }

    /**
     * 将选区矩形约束在屏幕边界内。
     */
    private fun clampToScreen() {
        val bounds = RectF(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
        selectionRect = RectUtil.clampToBounds(selectionRect, bounds)
    }

    // ==================== 窗口附加/移除 ====================

    /**
     * 通过 WindowManager 将悬浮窗附加到屏幕上。
     */
    fun attachToWindow() {
        if (isAttached) return

        // 获取屏幕尺寸
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 初始化选区
        initSelectionRect()

        val params = WindowManager.LayoutParams().apply {
            // Android 8.0+ (API 26+) 统一使用 TYPE_APPLICATION_OVERLAY
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // 关键 Flag：不拦截底层页面触控，不获取焦点
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            // 全屏布局
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        try {
            windowManager.addView(this, params)
            isAttached = true
        } catch (e: SecurityException) {
            // 悬浮窗权限未授予，静默处理
            isAttached = false
        }
    }

    /**
     * 从 WindowManager 移除悬浮窗。
     */
    fun detachFromWindow() {
        if (!isAttached) return
        try {
            windowManager.removeView(this)
        } catch (e: IllegalArgumentException) {
            // 已移除，忽略
        }
        isAttached = false
    }

    /**
     * 外部更新屏幕尺寸（横竖屏切换时调用）。
     */
    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        // 按比例缩放选区适应新屏幕
        initSelectionRect()
        invalidate()
    }
}