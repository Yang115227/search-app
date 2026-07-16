package com.smartsearch.app.feature.search.floatview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 功能面板悬浮窗 —— 在悬浮球附近弹出的功能选择面板，Canvas 绘制。
 *
 * # 视觉设计
 * - 半透明黑色背景遮罩（全屏）
 * - 中间白色圆角矩形面板
 * - 4 个功能按钮：录屏搜题、无障碍搜题、相机扫描、关闭悬浮窗
 * - 每个按钮：圆形绿色背景 + 下方文字
 *
 * # 触摸逻辑
 * - 点击遮罩区域（面板外）→ 关闭面板
 * - 点击按钮 → 通过回调通知外部
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，Canvas 绘制，无第三方依赖。
 */
class FunctionPanelView(context: Context) : View(context) {

    // ==================== 回调 ====================

    /** 关闭面板 */
    var onDismiss: (() -> Unit)? = null

    /** 点击录屏搜题 */
    var onScreenCaptureClick: (() -> Unit)? = null

    /** 点击无障碍搜题 */
    var onAccessibilityClick: (() -> Unit)? = null

    /** 点击相机扫描 */
    var onCameraClick: (() -> Unit)? = null

    /** 点击关闭悬浮窗 */
    var onCloseClick: (() -> Unit)? = null

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    /** 面板在屏幕上的位置（由外部传入） */
    private var panelScreenX = 0
    private var panelScreenY = 0
    private var panelWidth = 0
    private var panelHeight = 0

    // ==================== 尺寸常量 ====================

    private val density = context.resources.displayMetrics.density
    private val dp: Float.(Float) -> Float = { value -> value * density }

    /** 遮罩颜色：半透明黑 */
    private val maskColor = 0x80000000.toInt()

    /** 面板背景颜色：白色 */
    private val panelBgColor = Color.WHITE

    /** 面板圆角半径 */
    private val panelCornerRadius = 16f.dp(16f)

    /** 面板内边距 */
    private val panelPadding = 20f.dp(20f)

    /** 按钮之间的间距 */
    private val buttonSpacing = 16f.dp(16f)

    /** 按钮圆形半径 */
    private val buttonRadius = 32f.dp(32f)

    /** 按钮绿色背景 */
    private val buttonColor = Color.parseColor("#4CAF50")

    /** 按钮按下时的深绿色 */
    private val buttonPressedColor = Color.parseColor("#388E3C")

    /** 按钮图标文字大小 */
    private val iconTextSize = 20f.dp(20f)

    /** 按钮标签文字大小 */
    private val labelTextSize = 12f.dp(12f)

    /** 触摸热区扩大 */
    private val touchSlop = 8f.dp(8f)

    /** 屏幕尺寸 */
    private var screenWidth = 0
    private var screenHeight = 0

    // ==================== 按钮数据 ====================

    private data class ButtonItem(
        val icon: String,       // 图标文字（emoji 或简单字符）
        val label: String,      // 标签文字
        var centerX: Float,     // 圆形中心 X（相对面板坐标）
        var centerY: Float,     // 圆形中心 Y（相对面板坐标）
        val clickCallback: () -> Unit
    )

    private val buttons = mutableListOf<ButtonItem>()

    // ==================== 画笔 ====================

    /** 遮罩画笔 */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maskColor
        style = Paint.Style.FILL
    }

    /** 面板背景画笔 */
    private val panelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = panelBgColor
        style = Paint.Style.FILL
    }

    /** 面板阴影画笔 */
    private val panelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** 按钮圆形画笔（正常） */
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = buttonColor
        style = Paint.Style.FILL
    }

    /** 按钮圆形画笔（按下） */
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = buttonPressedColor
        style = Paint.Style.FILL
    }

    /** 图标文字画笔 */
    private val iconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = iconTextSize
        textAlign = Paint.Align.CENTER
    }

    /** 标签文字画笔 */
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
    }

    /** 面板标题画笔 */
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 16f.dp(16f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // ==================== 触摸状态 ====================

    private var pressedButtonIndex = -1

    // ==================== 初始化 ====================

    private fun initButtons() {
        buttons.clear()
        val panelContentWidth = panelWidth - panelPadding * 2
        val totalButtonWidth = buttonRadius * 2 * 2 + buttonSpacing // 2 列
        val startX = (panelContentWidth - totalButtonWidth) / 2f + panelPadding + buttonRadius
        val row1Y = panelPadding + buttonRadius + 40f.dp(40f) // 留出标题空间
        val row2Y = row1Y + buttonRadius * 2 + buttonSpacing + 24f.dp(24f) // 标签空间

        buttons.addAll(listOf(
            ButtonItem("📹", "录屏搜题", startX, row1Y, {
                onScreenCaptureClick?.invoke()
            }),
            ButtonItem("♿", "无障碍搜题", startX + buttonRadius * 2 + buttonSpacing, row1Y, {
                onAccessibilityClick?.invoke()
            }),
            ButtonItem("📷", "相机扫描", startX, row2Y, {
                onCameraClick?.invoke()
            }),
            ButtonItem("✕", "关闭悬浮窗", startX + buttonRadius * 2 + buttonSpacing, row2Y, {
                onCloseClick?.invoke()
            })
        ))
    }

    // ==================== Canvas 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── 第 1 层：全屏半透明黑色遮罩 ──
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // ── 第 2 层：面板阴影 ──
        val panelRect = calculatePanelRect()
        val shadowRect = RectF(
            panelRect.left + 2f.dp(2f),
            panelRect.top + 2f.dp(2f),
            panelRect.right + 2f.dp(2f),
            panelRect.bottom + 2f.dp(2f)
        )
        canvas.drawRoundRect(shadowRect, panelCornerRadius, panelCornerRadius, panelShadowPaint)

        // ── 第 3 层：面板白色背景 ──
        canvas.drawRoundRect(panelRect, panelCornerRadius, panelCornerRadius, panelBgPaint)

        // ── 第 4 层：面板标题 ──
        val titleText = "选择搜题方式"
        val titleY = panelRect.top + 36f.dp(36f)
        canvas.drawText(titleText, panelRect.centerX(), titleY, titlePaint)

        // ── 第 5 层：绘制按钮 ──
        drawButtons(canvas, panelRect)
    }

    /**
     * 计算面板矩形（相对于整个 View 的坐标）。
     */
    private fun calculatePanelRect(): RectF {
        // 面板在屏幕上的位置由 panelScreenX/Y 确定
        val left = panelScreenX.toFloat()
        val top = panelScreenY.toFloat()
        return RectF(left, top, left + panelWidth, top + panelHeight)
    }

    /**
     * 绘制所有功能按钮。
     */
    private fun drawButtons(canvas: Canvas, panelRect: RectF) {
        for (i in buttons.indices) {
            val btn = buttons[i]
            val cx = panelRect.left + btn.centerX
            val cy = panelRect.top + btn.centerY

            // 按钮圆形背景
            val paint = if (i == pressedButtonIndex) buttonPressedPaint else buttonPaint
            canvas.drawCircle(cx, cy, buttonRadius, paint)

            // 按钮边框（白色细线）
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            canvas.drawCircle(cx, cy, buttonRadius, borderPaint)

            // 图标文字（居中显示在圆形内）
            val iconY = cy - (iconPaint.fontMetrics.ascent + iconPaint.fontMetrics.descent) / 2f
            canvas.drawText(btn.icon, cx, iconY, iconPaint)

            // 标签文字（显示在圆形下方）
            val labelY = cy + buttonRadius + 20f.dp(20f)
            canvas.drawText(btn.label, cx, labelY, labelPaint)
        }
    }

    // ==================== 触摸事件处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButtonIndex = findButtonAt(x, y)
                if (pressedButtonIndex >= 0) {
                    invalidate()
                    return true
                }
                // 点击遮罩区域
                if (!isInsidePanel(x, y)) {
                    onDismiss?.invoke()
                    return true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedButtonIndex >= 0) {
                    val hit = isHitButton(x, y, buttons[pressedButtonIndex])
                    if (!hit) {
                        pressedButtonIndex = -1
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (pressedButtonIndex >= 0) {
                    val btn = buttons[pressedButtonIndex]
                    if (isHitButton(x, y, btn)) {
                        btn.clickCallback()
                    }
                    pressedButtonIndex = -1
                    invalidate()
                } else {
                    // 点击面板外区域关闭
                    if (!isInsidePanel(x, y)) {
                        onDismiss?.invoke()
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedButtonIndex = -1
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * 查找触摸点命中的按钮索引。
     */
    private fun findButtonAt(x: Float, y: Float): Int {
        for (i in buttons.indices) {
            if (isHitButton(x, y, buttons[i])) return i
        }
        return -1
    }

    /**
     * 判断触摸点是否命中指定按钮。
     */
    private fun isHitButton(x: Float, y: Float, btn: ButtonItem): Boolean {
        val panelRect = calculatePanelRect()
        val cx = panelRect.left + btn.centerX
        val cy = panelRect.top + btn.centerY
        val dx = x - cx
        val dy = y - cy
        val hitRadius = buttonRadius + touchSlop
        return dx * dx + dy * dy <= hitRadius * hitRadius
    }

    /**
     * 判断触摸点是否在面板内部。
     */
    private fun isInsidePanel(x: Float, y: Float): Boolean {
        val panelRect = calculatePanelRect()
        return panelRect.contains(x, y)
    }

    // ==================== 窗口附加/移除 ====================

    /**
     * 通过 WindowManager 将功能面板附加到屏幕上。
     *
     * @param screenX 面板左上角 X（屏幕坐标）
     * @param screenY 面板左上角 Y（屏幕坐标）
     * @param widthPx 面板宽度
     * @param heightPx 面板高度
     */
    fun attachToWindow(screenX: Int, screenY: Int, widthPx: Int, heightPx: Int) {
        if (isAttached) return

        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        panelScreenX = screenX
        panelScreenY = screenY
        panelWidth = widthPx
        panelHeight = heightPx

        initButtons()

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            // 全屏布局，面板内容通过 Canvas 绘制在指定位置
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        windowParams = params

        try {
            windowManager.addView(this, params)
            isAttached = true
        } catch (e: SecurityException) {
            isAttached = false
        }
    }

    /**
     * 从 WindowManager 移除功能面板。
     */
    fun detachFromWindow() {
        if (!isAttached) return
        try {
            windowManager.removeView(this)
        } catch (e: IllegalArgumentException) {
            // 已移除
        }
        isAttached = false
    }
}