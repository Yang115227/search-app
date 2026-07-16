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
import com.smartsearch.app.core.utils.RectUtil

/**
 * 答案展示悬浮窗 —— 绿色外边框 + 白色内容区域，Canvas 绘制。
 *
 * # 视觉设计
 * - 绿色（#4CAF50）外边框，圆角矩形，边框宽度 3dp
 * - 白色内容区域，显示答案文本和解析文本
 * - 左上角绘制返回箭头按钮（圆形 + 左箭头），点击返回选题框
 * - 右下角 L 形缩放控制点，拖动可改变窗口大小
 * - 顶部标题栏区域绘制"答案"标题
 *
 * # 触摸逻辑
 * | 触摸位置          | 行为                              |
 * |------------------|-----------------------------------|
 * | 返回箭头按钮      | 销毁当前弹窗，重新打开选题框         |
 * | 缩放控制点        | 拖动改变窗口大小                   |
 * | 其他区域          | 拖动整体窗口位置                   |
 *
 * # 窗口属性
 * - WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
 * - FLAG_NOT_FOCUSABLE：不拦截底层页面焦点
 * - 默认宽 300dp，高自适应（最小 150dp，最大 400dp）
 * - 默认位置：屏幕水平居中，纵向偏下 1/3 处
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，Canvas 绘制，无第三方依赖。
 */
class AnswerFloatWindow(private val context: Context) : View(context) {

    // ==================== 回调 ====================

    /** 点击返回箭头按钮 */
    var onBackPressed: (() -> Unit)? = null

    /** 点击关闭（外部可能通过其他方式关闭） */
    var onDismiss: (() -> Unit)? = null

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    // ==================== 尺寸常量 ====================

    private val density = context.resources.displayMetrics.density
    private val dp: Float.(Float) -> Float = { value -> value * density }

    /** 窗口默认宽度 */
    private val windowWidth = 300f.dp(300f)

    /** 窗口最小高度 */
    private val minWindowHeight = 150f.dp(150f)

    /** 窗口最大高度 */
    private val maxWindowHeight = 400f.dp(400f)

    /** 绿色外边框宽度 */
    private val borderWidth = 3f.dp(3f)

    /** 圆角半径 */
    private val cornerRadius = 12f.dp(12f)

    /** 标题栏高度 */
    private val titleBarHeight = 40f.dp(40f)

    /** 内边距 */
    private val padding = 16f.dp(16f)

    /** 返回箭头按钮半径 */
    private val backButtonRadius = 14f.dp(14f)

    /** 返回箭头线条长度 */
    private val backArrowLen = 6f.dp(6f)

    /** 返回箭头描边宽度 */
    private val backArrowStroke = 2.5f.dp(2.5f)

    /** 缩放控制点边长 */
    private val resizeHandleSize = 36f.dp(36f)

    /** 缩放控制点 L 形线条长度 */
    private val resizeHandleLineLen = 18f.dp(18f)

    /** 缩放控制点描边宽度 */
    private val resizeHandleStroke = 3f.dp(3f)

    /** 触摸热区扩大 */
    private val touchSlop = 12f.dp(12f)

    /** 窗口最小可缩放尺寸 */
    private val minResizeWidth = 200f.dp(200f)
    private val minResizeHeight = 100f.dp(100f)

    // ==================== 颜色常量 ====================

    /** 绿色外边框 */
    private val borderColor = Color.parseColor("#4CAF50")

    /** 白色内容背景 */
    private val backgroundColor = Color.WHITE

    /** 标题栏文字颜色 */
    private val titleTextColor = Color.parseColor("#333333")

    /** 答案文字颜色 */
    private val answerTextColor = Color.parseColor("#1B5E20")

    /** 解析文字颜色 */
    private val explanationTextColor = Color.parseColor("#666666")

    // ==================== 内容数据 ====================

    /** 答案文本 */
    private var answerText: String = ""

    /** 解析文本 */
    private var explanationText: String = ""

    // ==================== 当前窗口尺寸 ====================

    private var currentWidth = windowWidth
    private var currentHeight = minWindowHeight

    // ==================== 画笔 ====================

    /** 绿色外边框画笔 */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    /** 白色内容背景画笔 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }

    /** 返回箭头按钮背景圆画笔 */
    private val backBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0) // 极浅灰
        style = Paint.Style.FILL
    }

    /** 返回箭头线条画笔 */
    private val backArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = backArrowStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 标题文字画笔 */
    private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = titleTextColor
        textSize = 16f.dp(16f)
        isFakeBoldText = true
    }

    /** 答案文字画笔 */
    private val answerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = answerTextColor
        textSize = 18f.dp(18f)
        isFakeBoldText = true
    }

    /** 解析文字画笔 */
    private val explanationTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = explanationTextColor
        textSize = 14f.dp(14f)
    }

    /** 缩放控制点画笔 */
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = resizeHandleStroke
        strokeCap = Paint.Cap.ROUND
    }

    // ==================== 触摸状态 ====================

    private var touchMode = TouchMode.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var resizeStartWidth = 0f
    private var resizeStartHeight = 0f

    private enum class TouchMode {
        NONE, DRAG, RESIZE
    }

    // ==================== 静态布局缓存 ====================

    private var answerLayout: StaticLayout? = null
    private var explanationLayout: StaticLayout? = null

    // ==================== 公开方法 ====================

    /**
     * 设置答案内容并重新测量布局。
     * @param answer 答案文本
     * @param explanation 解析文本（可选）
     */
    fun setContent(answer: String, explanation: String = "") {
        this.answerText = answer
        this.explanationText = explanation
        buildTextLayouts()
        calculateWindowHeight()
        requestLayout()
        invalidate()
    }

    /**
     * 构建 StaticLayout 用于文字换行绘制。
     */
    private fun buildTextLayouts() {
        val textAreaWidth = (currentWidth - padding * 2 - borderWidth * 2).toInt()

        if (answerText.isNotEmpty() && textAreaWidth > 0) {
            answerLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(answerText, 0, answerText.length, answerTextPaint, textAreaWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4f.dp(4f), 1f)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(answerText, answerTextPaint, textAreaWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 4f.dp(4f), false)
            }
        }

        if (explanationText.isNotEmpty() && textAreaWidth > 0) {
            explanationLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(explanationText, 0, explanationText.length, explanationTextPaint, textAreaWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4f.dp(4f), 1f)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(explanationText, explanationTextPaint, textAreaWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 4f.dp(4f), false)
            }
        }
    }

    /**
     * 根据文本内容计算窗口合适高度。
     */
    private fun calculateWindowHeight() {
        val answerH = answerLayout?.height?.toFloat() ?: 0f
        val explanationH = explanationLayout?.height?.toFloat() ?: 0f

        // 标题栏 + 答案标签 + 答案内容 + 间距 + 解析标签 + 解析内容 + 底部 padding
        var contentH = titleBarHeight + padding // 标题栏
        if (answerText.isNotEmpty()) {
            contentH += 24f.dp(24f) // "答案" 标签行高
            contentH += answerH
        }
        if (explanationText.isNotEmpty()) {
            contentH += padding * 0.5f // 间距
            contentH += 24f.dp(24f) // "解析" 标签行高
            contentH += explanationH
        }
        contentH += padding // 底部 padding
        contentH += borderWidth * 2 // 边框

        currentHeight = contentH.coerceIn(minWindowHeight, maxWindowHeight)
    }

    // ==================== 测量 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(currentWidth.toInt(), currentHeight.toInt())
    }

    // ==================== Canvas 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val halfBorder = borderWidth / 2f

        // 内容区域矩形（边框内区域）
        val contentRect = RectF(halfBorder, halfBorder, w - halfBorder, h - halfBorder)

        // ── 第 1 层：白色内容背景（圆角矩形） ──
        canvas.drawRoundRect(contentRect, cornerRadius, cornerRadius, bgPaint)

        // ── 第 2 层：绿色外边框（圆角矩形） ──
        canvas.drawRoundRect(contentRect, cornerRadius, cornerRadius, borderPaint)

        // ── 第 3 层：标题栏分割线 ──
        val dividerY = titleBarHeight + halfBorder
        canvas.drawLine(
            padding + halfBorder, dividerY,
            w - padding - halfBorder, dividerY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E0E0E0")
                strokeWidth = 1f
            })

        // ── 第 4 层：返回箭头按钮 ──
        drawBackButton(canvas, halfBorder)

        // ── 第 5 层：标题文字 ──
        val titleText = "答案"
        val titleX = (w - titleTextPaint.measureText(titleText)) / 2f
        val titleY = halfBorder + (titleBarHeight - titleTextPaint.fontMetrics.let {
            it.descent - it.ascent
        }) / 2f - titleTextPaint.fontMetrics.ascent
        canvas.drawText(titleText, titleX, titleY, titleTextPaint)

        // ── 第 6 层：答案内容文字 ──
        drawContentText(canvas, halfBorder)

        // ── 第 7 层：缩放控制点 ──
        drawResizeHandle(canvas, w, h)
    }

    /**
     * 绘制左上角返回箭头按钮。
     */
    private fun drawBackButton(canvas: Canvas, halfBorder: Float) {
        val cx = halfBorder + padding + backButtonRadius
        val cy = halfBorder + titleBarHeight / 2f

        // 背景圆
        canvas.drawCircle(cx, cy, backButtonRadius, backBgPaint)

        // 左箭头 "<"
        val len = backArrowLen
        // 水平线
        canvas.drawLine(cx - len, cy, cx + len, cy, backArrowPaint)
        // 指向左上方的斜线（箭头尖端）
        canvas.drawLine(cx - len, cy, cx - len + len * 0.6f, cy - len * 0.6f, backArrowPaint)
        // 指向左下方的斜线（箭头尖端）
        canvas.drawLine(cx - len, cy, cx - len + len * 0.6f, cy + len * 0.6f, backArrowPaint)
    }

    /**
     * 绘制答案/解析内容文字。
     */
    private fun drawContentText(canvas: Canvas, halfBorder: Float) {
        val textX = halfBorder + padding
        var textY = halfBorder + titleBarHeight + padding + 4f.dp(4f)

        // 答案标签
        if (answerText.isNotEmpty()) {
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = answerTextColor
                textSize = 13f.dp(13f)
                isFakeBoldText = true
            }
            canvas.drawText("答案", textX, textY, labelPaint)
            textY += 24f.dp(24f)

            // 答案内容
            answerLayout?.let { layout ->
                canvas.save()
                canvas.translate(textX, textY)
                layout.draw(canvas)
                canvas.restore()
                textY += layout.height + 4f.dp(4f)
            }
        }

        // 解析标签
        if (explanationText.isNotEmpty()) {
            textY += 4f.dp(4f)
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = explanationTextColor
                textSize = 13f.dp(13f)
                isFakeBoldText = true
            }
            canvas.drawText("解析", textX, textY, labelPaint)
            textY += 24f.dp(24f)

            // 解析内容
            explanationLayout?.let { layout ->
                canvas.save()
                canvas.translate(textX, textY)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    /**
     * 绘制右下角 L 形缩放控制点。
     */
    private fun drawResizeHandle(canvas: Canvas, w: Float, h: Float) {
        val x = w - borderWidth - 4f.dp(4f)
        val y = h - borderWidth - 4f.dp(4f)
        val len = resizeHandleLineLen

        // L 形拐角
        canvas.drawLine(x - len, y, x, y, resizePaint)
        canvas.drawLine(x, y - len, x, y, resizePaint)
    }

    // ==================== 触摸事件处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> handleTouchMove(x, y, event.rawX, event.rawY)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handleTouchUp(x, y, event.rawX, event.rawY)
        }
        return true
    }

    /**
     * 手指按下：判断触摸位置。
     */
    private fun handleTouchDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y

        val params = windowParams ?: return
        windowStartX = params.x
        windowStartY = params.y

        // 优先级 1：点击返回箭头按钮
        val backCx = borderWidth / 2f + padding + backButtonRadius
        val backCy = borderWidth / 2f + titleBarHeight / 2f
        val backButtonRect = RectF(
            backCx - backButtonRadius - touchSlop,
            backCy - backButtonRadius - touchSlop,
            backCx + backButtonRadius + touchSlop,
            backCy + backButtonRadius + touchSlop
        )
        if (backButtonRect.contains(x, y)) {
            onBackPressed?.invoke()
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 2：点击缩放控制点
        val handleRect = RectF(
            width - resizeHandleSize - touchSlop,
            height - resizeHandleSize - touchSlop,
            width.toFloat() + touchSlop,
            height.toFloat() + touchSlop
        )
        if (handleRect.contains(x, y)) {
            touchMode = TouchMode.RESIZE
            resizeStartWidth = currentWidth
            resizeStartHeight = currentHeight
            return
        }

        // 优先级 3：拖动整体窗口
        touchMode = TouchMode.DRAG
    }

    /**
     * 手指移动。
     */
    private fun handleTouchMove(x: Float, y: Float, rawX: Float, rawY: Float) {
        when (touchMode) {
            TouchMode.DRAG -> {
                val params = windowParams ?: return
                val dx = (x - touchStartX).toInt()
                val dy = (y - touchStartY).toInt()
                params.x = windowStartX + dx
                params.y = windowStartY + dy

                try {
                    windowManager.updateViewLayout(this, params)
                } catch (e: IllegalArgumentException) {
                    // 视图已被移除
                }
            }

            TouchMode.RESIZE -> {
                val dx = x - touchStartX
                val dy = y - touchStartY

                var newW = (resizeStartWidth + dx).coerceAtLeast(minResizeWidth)
                var newH = (resizeStartHeight + dy).coerceAtLeast(minResizeHeight)

                // 屏幕边界限制
                val metrics = context.resources.displayMetrics
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()

                val params = windowParams ?: return
                if (params.x + newW > screenW) newW = screenW - params.x
                if (params.y + newH > screenH) newH = screenH - params.y

                currentWidth = newW
                currentHeight = newH

                // 重新构建文本布局
                buildTextLayouts()
                requestLayout()
                invalidate()

                // 更新窗口尺寸
                params.width = newW.toInt()
                params.height = newH.toInt()
                try {
                    windowManager.updateViewLayout(this, params)
                } catch (e: IllegalArgumentException) {
                    // 视图已被移除
                }
            }

            TouchMode.NONE -> { /* 忽略 */ }
        }
    }

    /**
     * 手指抬起。
     */
    private fun handleTouchUp(x: Float, y: Float, rawX: Float, rawY: Float) {
        touchMode = TouchMode.NONE
    }

    // ==================== 窗口附加/移除 ====================

    /**
     * 通过 WindowManager 将答案弹窗附加到屏幕上。
     */
    fun attachToWindow() {
        if (isAttached) return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // 默认位置：水平居中，纵向偏下
        val defaultX = ((screenW - currentWidth) / 2).toInt()
        val defaultY = (screenH * 0.45f).toInt()

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            width = currentWidth.toInt()
            height = currentHeight.toInt()
            gravity = Gravity.TOP or Gravity.START
            x = defaultX
            y = defaultY

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
     * 从 WindowManager 移除答案弹窗。
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