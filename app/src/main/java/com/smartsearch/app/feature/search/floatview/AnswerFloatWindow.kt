package com.smartsearch.app.feature.search.floatview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 底部答案弹窗 —— 绿色底部常驻栏，Canvas 绘制。
 *
 * # 视觉设计
 * - 底部全宽绿色（#4CAF50）背景条，圆角顶部
 * - 左侧：X 关闭按钮
 * - 中间：答案文本（自动换行，最多 2 行）
 * - 右侧：绿色「选区」按钮
 * - 白色分割线分隔各区域
 *
 * # 触摸逻辑
 * | 触摸位置          | 行为                              |
 * |------------------|-----------------------------------|
 * | X 关闭按钮        | 退出连续搜题模式，回到悬浮球         |
 * | 「选区」按钮      | 重新唤起选区框，调整识别范围         |
 * | 其他区域          | 拖动整体窗口位置                   |
 *
 * # 窗口属性
 * - WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
 * - FLAG_NOT_FOCUSABLE：不拦截底层页面焦点
 * - 默认宽度：屏幕宽度 - 16dp 边距
 * - 默认高度：自适应（约 120dp）
 * - 默认位置：屏幕底部居中
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，Canvas 绘制，无第三方依赖。
 */
class AnswerFloatWindow(private val context: Context) : View(context) {

    // ==================== 回调 ====================

    /** 点击关闭按钮 */
    var onDismiss: (() -> Unit)? = null

    /** 点击「选区」按钮，重新唤起选区框 */
    var onSelectAreaClick: (() -> Unit)? = null

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    // ==================== 尺寸常量 ====================

    private val density = context.resources.displayMetrics.density
    private val dp: Float.(Float) -> Float = { value -> value * density }

    /** 窗口高度 */
    private val windowHeight = 120f.dp(120f)

    /** 底部边距（距离屏幕底部） */
    private val bottomMargin = 24f.dp(24f)

    /** 水平边距 */
    private val horizontalMargin = 8f.dp(8f)

    /** 圆角半径（顶部两个角） */
    private val cornerRadius = 12f.dp(12f)

    /** 左右内边距 */
    private val padding = 12f.dp(12f)

    /** 关闭按钮半径 */
    private val closeButtonRadius = 16f.dp(16f)

    /** 关闭按钮 X 线条长度 */
    private val closeCrossLen = 7f.dp(7f)

    /** 关闭按钮描边宽度 */
    private val closeButtonStroke = 2.5f.dp(2.5f)

    /** 「选区」按钮宽度 */
    private val selectAreaBtnWidth = 72f.dp(72f)

    /** 「选区」按钮高度 */
    private val selectAreaBtnHeight = 36f.dp(36f)

    /** 「选区」按钮圆角 */
    private val selectAreaBtnRadius = 8f.dp(8f)

    /** 触摸热区扩大 */
    private val touchSlop = 12f.dp(12f)

    /** 拖动判定最小距离 */
    private val dragThreshold = 10f.dp(10f)

    // ==================== 颜色常量 ====================

    /** 背景色（绿色） */
    private val bgColor = Color.parseColor("#4CAF50")

    /** 按钮背景色（白色半透明） */
    private val btnBgColor = Color.argb(30, 255, 255, 255)

    /** 分割线颜色 */
    private val dividerColor = Color.argb(60, 255, 255, 255)

    /** 关闭按钮颜色 */
    private val closeBtnColor = Color.argb(180, 255, 255, 255)

    /** 文字颜色（白色） */
    private val textColor = Color.WHITE

    /** 选区按钮文字颜色 */
    private val selectBtnTextColor = Color.parseColor("#4CAF50")

    /** 选区按钮背景色 */
    private val selectBtnBgColor = Color.WHITE

    // ==================== 内容数据 ====================

    /** 答案文本 */
    private var answerText: String = ""

    /** 解析文本（备选，当答案为空时显示） */
    private var explanationText: String = ""

    // ==================== 当前窗口尺寸 ====================

    private var currentWidth = 0
    private var currentHeight = windowHeight.toInt()

    // ==================== 画笔 ====================

    /** 绿色背景画笔 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }

    /** 分割线画笔 */
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dividerColor
        strokeWidth = 1.5f.dp(1.5f)
    }

    /** 关闭按钮画笔 */
    private val closeBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = closeBtnColor
        style = Paint.Style.STROKE
        strokeWidth = closeButtonStroke
        strokeCap = Paint.Cap.ROUND
    }

    /** 关闭按钮背景圆画笔 */
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = btnBgColor
        style = Paint.Style.FILL
    }

    /** 答案文字画笔 */
    private val answerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 15f.dp(15f)
        isFakeBoldText = true
    }

    /** 解析文字画笔（较小） */
    private val explanationTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 13f.dp(13f)
        alpha = 200
    }

    /** 选区按钮文字画笔 */
    private val selectBtnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = selectBtnTextColor
        textSize = 14f.dp(14f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    /** 选区按钮背景画笔 */
    private val selectBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = selectBtnBgColor
        style = Paint.Style.FILL
    }

    // ==================== 触摸状态 ====================

    private var touchMode = TouchMode.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var isDragging = false

    private enum class TouchMode {
        NONE, DRAG
    }

    // ==================== 静态布局缓存 ====================

    private var answerLayout: StaticLayout? = null

    // ==================== 公开方法 ====================

    /**
     * 设置答案内容并重新绘制。
     * @param answer 答案文本
     * @param explanation 解析文本（可选）
     */
    fun setContent(answer: String, explanation: String = "") {
        this.answerText = answer
        this.explanationText = explanation
        buildTextLayout()
        invalidate()
    }

    /**
     * 构建 StaticLayout 用于文字换行绘制。
     */
    private fun buildTextLayout() {
        // 文本区域宽度 = 窗口宽度 - 左侧关闭按钮区 - 右侧选区按钮区 - 内边距
        val closeArea = padding + closeButtonRadius * 2 + padding
        val selectArea = padding + selectAreaBtnWidth + padding
        val textAreaWidth = (currentWidth - closeArea - selectArea - padding * 2).coerceAtLeast(50)

        val displayText = if (answerText.isNotEmpty()) answerText else explanationText

        if (displayText.isNotEmpty() && textAreaWidth > 0) {
            val paint = if (answerText.isNotEmpty()) answerTextPaint else explanationTextPaint
            // 最大 2 行高度限制
            val maxLines = 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                answerLayout = StaticLayout.Builder.obtain(
                    displayText, 0, displayText.length, paint, textAreaWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .setLineSpacing(2f.dp(2f), 1f)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                answerLayout = StaticLayout(
                    displayText, paint, textAreaWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1f, 2f.dp(2f), false
                )
            }
        }
    }

    // ==================== 测量 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(currentWidth, currentHeight)
    }

    // ==================== Canvas 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // ── 第 1 层：绿色圆角矩形背景（顶部圆角） ──
        val bgRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // ── 第 2 层：左侧关闭按钮 ──
        drawCloseButton(canvas, h)

        // ── 第 2b 层：关闭按钮右侧分割线 ──
        val closeRight = padding + closeButtonRadius * 2 + padding
        canvas.drawLine(closeRight, padding, closeRight, h - padding, dividerPaint)

        // ── 第 3 层：中间答案文本 ──
        drawAnswerText(canvas, h, closeRight)

        // ── 第 4 层：右侧「选区」按钮 ──
        drawSelectAreaButton(canvas, w, h)
    }

    /**
     * 绘制左侧关闭按钮。
     */
    private fun drawCloseButton(canvas: Canvas, h: Float) {
        val cx = padding + closeButtonRadius
        val cy = h / 2f

        // 背景圆
        canvas.drawCircle(cx, cy, closeButtonRadius, closeBgPaint)

        // X 形状
        val half = closeCrossLen
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, closeBtnPaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, closeBtnPaint)
    }

    /**
     * 绘制中间答案文本。
     */
    private fun drawAnswerText(canvas: Canvas, h: Float, leftEdge: Float) {
        val textLeft = leftEdge + padding
        val textCenterY = h / 2f

        answerLayout?.let { layout ->
            val textHeight = layout.height
            val textTop = textCenterY - textHeight / 2f
            canvas.save()
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
        } ?: run {
            // 无内容时显示提示文字
            val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 13f.dp(13f)
                alpha = 150
            }
            val hintText = "等待识别结果..."
            val textY = textCenterY - (hintPaint.fontMetrics.ascent + hintPaint.fontMetrics.descent) / 2f
            canvas.drawText(hintText, textLeft, textY, hintPaint)
        }
    }

    /**
     * 绘制右侧「选区」按钮。
     */
    private fun drawSelectAreaButton(canvas: Canvas, w: Float, h: Float) {
        val btnRight = w - padding
        val btnLeft = btnRight - selectAreaBtnWidth
        val btnCenterY = h / 2f
        val btnTop = btnCenterY - selectAreaBtnHeight / 2f
        val btnBottom = btnCenterY + selectAreaBtnHeight / 2f

        // 白色圆角矩形背景
        val btnRect = RectF(btnLeft, btnTop, btnRight, btnBottom)
        canvas.drawRoundRect(btnRect, selectAreaBtnRadius, selectAreaBtnRadius, selectBtnBgPaint)

        // 「选区」文字
        val text = "选区"
        val textY = btnCenterY - (selectBtnTextPaint.fontMetrics.ascent + selectBtnTextPaint.fontMetrics.descent) / 2f
        val textX = (btnLeft + btnRight) / 2f
        canvas.drawText(text, textX, textY, selectBtnTextPaint)

        // 按钮左侧分割线
        canvas.drawLine(btnLeft - padding, padding, btnLeft - padding, h - padding, dividerPaint)
    }

    // ==================== 触摸事件处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event.rawX, event.rawY)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handleTouchUp(x, y)
        }
        return true
    }

    /**
     * 手指按下：判断触摸位置。
     */
    private fun handleTouchDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y
        isDragging = false

        val params = windowParams ?: return
        windowStartX = params.x
        windowStartY = params.y

        // 优先级 1：点击 X 关闭按钮（左侧）
        val closeCx = padding + closeButtonRadius
        val closeCy = height / 2f
        val closeBtnRect = RectF(
            closeCx - closeButtonRadius - touchSlop,
            closeCy - closeButtonRadius - touchSlop,
            closeCx + closeButtonRadius + touchSlop,
            closeCy + closeButtonRadius + touchSlop
        )
        if (closeBtnRect.contains(x, y)) {
            onDismiss?.invoke()
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 2：点击「选区」按钮（右侧）
        val btnRight = width - padding
        val btnLeft = btnRight - selectAreaBtnWidth
        val btnCenterY = height / 2f
        val btnTop = btnCenterY - selectAreaBtnHeight / 2f - touchSlop
        val btnBottom = btnCenterY + selectAreaBtnHeight / 2f + touchSlop
        val selectBtnRect = RectF(
            btnLeft - touchSlop, btnTop, btnRight + touchSlop, btnBottom
        )
        if (selectBtnRect.contains(x, y)) {
            onSelectAreaClick?.invoke()
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 3：拖动整体窗口
        touchMode = TouchMode.DRAG
    }

    /**
     * 手指移动。
     */
    private fun handleTouchMove(rawX: Float, rawY: Float) {
        if (touchMode != TouchMode.DRAG) return

        val dx = rawX - touchStartX
        val dy = rawY - touchStartY

        // 拖动判定
        if (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold) {
            isDragging = true
        }

        if (isDragging) {
            val params = windowParams ?: return
            params.x = (windowStartX + dx).toInt()
            params.y = (windowStartY + dy).toInt()

            // 约束在屏幕范围内
            val metrics = context.resources.displayMetrics
            params.x = params.x.coerceIn(0, metrics.widthPixels - currentWidth)
            params.y = params.y.coerceIn(0, metrics.heightPixels - currentHeight)

            try {
                windowManager.updateViewLayout(this, params)
            } catch (e: IllegalArgumentException) {
                // 视图已被移除
            }
        }
    }

    /**
     * 手指抬起。
     */
    private fun handleTouchUp(x: Float, y: Float) {
        touchMode = TouchMode.NONE
        isDragging = false
    }

    // ==================== 窗口附加/移除 ====================

    /**
     * 通过 WindowManager 将底部答案弹窗附加到屏幕上。
     */
    fun attachToWindow() {
        if (isAttached) return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        currentWidth = (screenW - (horizontalMargin * 2).toInt())

        // 默认位置：屏幕底部居中
        val defaultX = horizontalMargin.toInt()
        val defaultY = screenH - currentHeight - bottomMargin.toInt()

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            width = currentWidth
            height = currentHeight
            gravity = Gravity.TOP or Gravity.START
            x = defaultX
            y = defaultY

            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        windowParams = params

        // 构建文本布局（需要 currentWidth 已确定）
        buildTextLayout()

        try {
            windowManager.addView(this, params)
            isAttached = true
        } catch (e: SecurityException) {
            isAttached = false
        }
    }

    /**
     * 从 WindowManager 移除底部答案弹窗。
     */
    fun detachFromWindow() {
        if (!isAttached) return
        try {
            windowManager.removeView(this)
        } catch (e: IllegalArgumentException) {
            // 已移除
        }
        isAttached = false
        windowParams = null
    }
}