package com.smartsearch.app.feature.search.floatview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.view.WindowManager
import com.smartsearch.app.core.utils.RectUtil

/**
 * 选题框悬浮窗 —— 纯透明悬浮窗 + 中间可选区域。
 *
 * # 视觉设计
 * - 外层 View 完全透明，无背景，不渲染遮罩
 * - 只渲染用户选区框（白色边框 + 四角高亮 + 关闭按钮 + 缩放控制点 + 确认按钮）
 * - 窗口尺寸 WRAP_CONTENT，仅包裹选区 + 按钮区域
 *
 * # 触摸逻辑
 * | 触摸位置               | 行为                              |
 * |-----------------------|-----------------------------------|
 * | 选区关闭按钮           | 点击关闭（取消选题）                |
 * | 选区内（非按钮区）      | 拖动整体选区                      |
 * | X 关闭按钮             | 点击关闭（取消选题）                |
 * | 右下角缩放控制点        | 拖动改变选区大小                   |
 *
 * # 窗口属性
 * - WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE：不拦截底层页面触控事件
 * - TYPE_APPLICATION_OVERLAY：Android 8+ 标准悬浮窗类型
 * - WRAP_CONTENT 尺寸，PixelFormat.TRANSLUCENT
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，Canvas 绘制，无第三方依赖。
 */
class FloatSelectOverlay(private val context: Context) : View(context) {

    init {
        // 确保 onDraw 被调用（View 默认 willNotDraw=false，但显式设置更安全）
        setWillNotDraw(false)
    }

    // ==================== 回调 ====================

    /** 点击关闭按钮时的回调 */
    var onDismiss: (() -> Unit)? = null

    /** 用户确认选区后的回调，参数为屏幕坐标系下的选区矩形 */
    var onRectConfirmed: ((Rect) -> Unit)? = null

    /** 用户点击"开始搜题"按钮的回调（连续搜题模式） */
    var onStartContinuousSearch: ((Rect) -> Unit)? = null

    /** 选区变化回调（拖拽/缩放时触发，用于连续搜题模式更新识别范围） */
    var onSelectionChanged: ((Rect) -> Unit)? = null

    /**
     * 选区保存回调：手指拖动/缩放抬起后，将最新选区保存到持久化存储。
     * 由 FloatWindowManager.showSelectOverlay 设置，调用 SelectionPrefs.save() 落盘。
     */
    var onSaveRect: ((Rect) -> Unit)? = null

    /** 是否处于连续搜题模式 */
    var isContinuousMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 调整模式标记。
     * 在连续搜题模式下，点击「选区」按钮重新唤起选区框时设为 true。
     * 调整模式下，手指抬起后自动隐藏选区框并触发 onSelectionChanged。
     */
    var isAdjustmentMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 调整模式回调：选区调整完成（手指抬起），自动隐藏。
     * 参数为调整后的选区矩形。
     */
    var onAdjustmentComplete: ((Rect) -> Unit)? = null

    /** 窗口已附加到 WindowManager 的回调（onAttachedToWindow 触发） */
    var onWindowAttached: (() -> Unit)? = null

    /** 窗口已从 WindowManager 分离的回调（onDetachedFromWindow 触发） */
    var onWindowDetached: (() -> Unit)? = null

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var isAttached = false

    /**
     * 保存窗口 LayoutParams，拖拽/缩放手势结束后更新窗口位置。
     * 在 attachToWindow 中初始化，在 updateWindowPosition 中更新 x/y。
     */
    private var windowParams: WindowManager.LayoutParams? = null

    // ==================== 尺寸常量（dp 转 px） ====================

    private val density = context.resources.displayMetrics.density
    private val dp: Float.(Float) -> Float = { value -> value * density }

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

    // ==================== 确认按钮尺寸 ====================

    /** 确认按钮宽度 */
    private val confirmButtonWidth = 120f.dp(120f)

    /** 确认按钮高度 */
    private val confirmButtonHeight = 40f.dp(40f)

    /** 确认按钮与选区底部间距 */
    private val confirmButtonMargin = 20f.dp(20f)

    // ==================== 选区矩形（屏幕坐标） ====================

    /** 当前选区矩形，初始化时居中，占屏幕宽高 40% */
    private var selectionRect = RectF()

    /** 获取当前选区屏幕坐标（整型 Rect） */
    fun getSelectionRect(): android.graphics.Rect {
        return android.graphics.Rect(
            selectionRect.left.toInt(),
            selectionRect.top.toInt(),
            selectionRect.right.toInt(),
            selectionRect.bottom.toInt()
        )
    }

    /**
     * 恢复之前保存的选区位置，并自动适配屏幕边界。
     * 如果选区超出屏幕范围，会自动调整到边界内。
     * 用于连续搜题模式下点击「选区」按钮重新唤起选区框时恢复上次选区。
     */
    fun setSelectionRect(rect: android.graphics.Rect) {
        Log.d("【SELECT_LOG】", "setSelectionRect 入口: rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) screen=${screenWidth}x${screenHeight}")
        // 边界适配：确保选区不超出屏幕范围
        val clampedRect = clampRectToScreen(rect)
        Log.d("【SELECT_LOG】", "setSelectionRect clamp后: clamped=(${clampedRect.left},${clampedRect.top},${clampedRect.right},${clampedRect.bottom})")
        selectionRect.set(
            clampedRect.left.toFloat(),
            clampedRect.top.toFloat(),
            clampedRect.right.toFloat(),
            clampedRect.bottom.toFloat()
        )
        // 强制刷新视图：invalidate 重绘 Canvas，requestLayout 触发父布局重新测量
        invalidate()
        requestLayout()
        // 窗口已挂载时更新窗口位置
        if (isAttached) {
            updateWindowPosition()
        }
        Log.d("【SELECT_LOG】", "setSelectionRect 完成: invalidate+requestLayout 已调用")
    }

    /**
     * 将矩形限制在屏幕边界内，确保最小尺寸。
     * 兼容不同分辨率设备：最小尺寸使用相对比例 + 绝对最小值双保险。
     * 对异形屏（刘海屏、挖孔屏）也做了边界适配。
     */
    private fun clampRectToScreen(rect: android.graphics.Rect): android.graphics.Rect {
        val minW = (screenWidth * 0.15f).toInt().coerceIn(80, 200)
        val minH = (screenHeight * 0.10f).toInt().coerceIn(60, 150)
        var left = rect.left.coerceIn(0, screenWidth - minW)
        var top = rect.top.coerceIn(0, screenHeight - minH)
        var right = rect.right.coerceIn(left + minW, screenWidth)
        var bottom = rect.bottom.coerceIn(top + minH, screenHeight)
        // 二次调整：如果 right/bottom 超出范围，保持矩形宽高不变，整体回拉
        if (right > screenWidth) {
            val width = right - left
            right = screenWidth
            left = (right - width).coerceAtLeast(0)
        }
        if (bottom > screenHeight) {
            val height = bottom - top
            bottom = screenHeight
            top = (bottom - height).coerceAtLeast(0)
        }
        // 三次调整：如果 left/top 被拉出屏幕左/上边界，再次回拉
        if (left < 0) {
            right -= left
            left = 0
        }
        if (top < 0) {
            bottom -= top
            top = 0
        }
        // 确保最小尺寸（经过上述调整后再次检查）
        if (right - left < minW) right = left + minW
        if (right > screenWidth) { left = screenWidth - minW; right = screenWidth }
        if (bottom - top < minH) bottom = top + minH
        if (bottom > screenHeight) { top = screenHeight - minH; bottom = screenHeight }
        return android.graphics.Rect(left, top, right, bottom)
    }

    // ==================== 画笔 ====================

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

    // ==================== 确认按钮画笔 ====================

    /** 确认按钮背景画笔（绿色圆角矩形） */
    private val confirmBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }

    /** 确认按钮文字画笔 */
    private val confirmTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f.dp(15f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    /** 连续搜题模式指示文字画笔 */
    private val continuousLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        textSize = 13f.dp(13f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // ==================== 触摸状态 ====================

    /** 防抖死区阈值（像素） */
    private val touchDeadZone = 5f.dp(5f)

    /** 当前触摸模式 */
    private var touchMode = TouchMode.NONE

    /** 触摸起始位置 */
    private var touchStartX = 0f
    private var touchStartY = 0f

    /** 上次有效触摸位置（用于防抖） */
    private var lastValidX = 0f
    private var lastValidY = 0f

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

    // ==================== 窗口位置偏移（屏幕坐标 → 视图坐标） ====================

    /** 窗口在屏幕上的 X 偏移（视图原点 = 屏幕 selectionRect.left） */
    private var windowOffsetX = 0
    /** 窗口在屏幕上的 Y 偏移（视图原点 = 屏幕 selectionRect.top） */
    private var windowOffsetY = 0

    // ==================== 屏幕尺寸缓存 ====================

    /** 屏幕宽度缓存 */
    var screenWidth = 0
        private set
    /** 屏幕高度缓存 */
    var screenHeight = 0
        private set

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

    /**
     * 计算视图的测量尺寸（基于选区 + 控制按钮区域）。
     * 视图原点 = 选区左上角，所以视图尺寸 = 选区宽/高 + 四周按钮/控制点间距。
     */
    private fun calcViewExtent(): Pair<Int, Int> {
        val extraLeft = (closeButtonRadius * 0.3f).toInt()
        val extraTop = (closeButtonRadius * 0.3f + closeButtonRadius + 4f.dp()).toInt()
        val extraRight = (resizeHandleSize + 4f.dp()).toInt()
        val extraBottom = (confirmButtonMargin + confirmButtonHeight + 20f.dp()).toInt()
        val w = selectionRect.width().toInt() + extraLeft + extraRight
        val h = selectionRect.height().toInt() + extraTop + extraBottom
        return Pair(w, h)
    }

    // ==================== 视图测量（WRAP_CONTENT） ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = calcViewExtent()
        setMeasuredDimension(w, h)
    }

    // ==================== 坐标转换 ====================

    /** 将视图坐标转换为屏幕坐标 X */
    private fun toScreenX(viewX: Float) = viewX + windowOffsetX
    /** 将视图坐标转换为屏幕坐标 Y */
    private fun toScreenY(viewY: Float) = viewY + windowOffsetY
    /** 获取视图坐标系下的选区矩形（视图原点 = 选区左上角） */
    private val viewRect: RectF
        get() = RectF(0f, 0f, selectionRect.width(), selectionRect.height())

    // ==================== Canvas 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("【SELECT_LOG】", "onDraw 绘制: viewRect=(${viewRect.left.toInt()},${viewRect.top.toInt()},${viewRect.right.toInt()},${viewRect.bottom.toInt()}) width=${width} height=${height}")

        if (selectionRect.isEmpty || selectionRect.width() <= 0 || selectionRect.height() <= 0) {
            Log.d("【SELECT_LOG】", "onDraw: 选区为空, 跳过绘制")
            return
        }

        val vr = viewRect

        // ── 第 1 层：选区边框 ──
        canvas.drawRect(vr, borderPaint)

        // ── 第 2 层：四角高亮短线 ──
        drawCornerHighlights(canvas, vr)

        // ── 第 3 层：右上角 X 关闭按钮 ──
        drawCloseButton(canvas, vr)

        // ── 第 4 层：右下角缩放控制点 ──
        drawResizeHandle(canvas, vr)

        // ── 第 5 层：确认选区按钮 ──
        drawConfirmButton(canvas, vr)

        // 连续搜题标签已在 drawConfirmButton 中绘制
    }

    /**
     * 绘制选区四角的高亮短线（相机取景框风格）。
     * @param vr 视图坐标系下的选区矩形
     */
    private fun drawCornerHighlights(canvas: Canvas, vr: RectF) {
        val cornerLen = 16f.dp(16f)
        val r = vr

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
     * @param vr 视图坐标系下的选区矩形
     */
    private fun drawCloseButton(canvas: Canvas, vr: RectF) {
        val cx = vr.right + closeButtonRadius * 0.3f
        val cy = vr.top - closeButtonRadius * 0.3f

        // 背景圆
        canvas.drawCircle(cx, cy, closeButtonRadius, closeBgPaint)

        // X 线条
        val half = closeCrossLength
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, closeLinePaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, closeLinePaint)
    }

    /**
     * 绘制右下角 L 形缩放控制点。
     * @param vr 视图坐标系下的选区矩形
     */
    private fun drawResizeHandle(canvas: Canvas, vr: RectF) {
        val r = vr
        val x = r.right - 4f.dp(4f)
        val y = r.bottom - 4f.dp(4f)
        val len = resizeHandleLineLen

        // L 形：右下角拐角，水平线向左 + 竖线向上
        // 水平线（从右下角向左）
        canvas.drawLine(x - len, y, x, y, resizePaint)
        // 竖线（从右下角向上）
        canvas.drawLine(x, y - len, x, y, resizePaint)
    }

    /**
     * 绘制底部居中确认选区按钮。
     *
     * 绿色圆角矩形 + 白色文字。
     * 按钮位于选区底部下方 [confirmButtonMargin] 像素处，水平居中。
     * - 普通模式：显示"开始搜题"
     * - 连续搜题模式：显示"搜题中..."
     * @param vr 视图坐标系下的选区矩形
     */
    private fun drawConfirmButton(canvas: Canvas, vr: RectF) {
        val r = vr
        val btnCenterX = r.centerX()
        val btnTop = r.bottom + confirmButtonMargin
        val btnLeft = btnCenterX - confirmButtonWidth / 2f
        val btnRight = btnCenterX + confirmButtonWidth / 2f
        val btnBottom = btnTop + confirmButtonHeight

        // 绿色圆角矩形背景
        val btnRect = RectF(btnLeft, btnTop, btnRight, btnBottom)
        canvas.drawRoundRect(btnRect, 12f.dp(12f), 12f.dp(12f), confirmBgPaint)

        // 白色文字
        val textY = btnTop + confirmButtonHeight / 2f -
                (confirmTextPaint.fontMetrics.ascent + confirmTextPaint.fontMetrics.descent) / 2f
        val buttonText = if (isContinuousMode) "搜题中..." else "开始搜题"
        canvas.drawText(buttonText, btnCenterX, textY, confirmTextPaint)

        // 连续搜题模式：在按钮上方显示"连续搜题"标签
        if (isContinuousMode) {
            val labelY = btnTop - 8f.dp(8f)
            canvas.drawText("连续搜题中...", btnCenterX, labelY, continuousLabelPaint)
        }
    }

    // ==================== 触摸事件处理 ====================

    /** 获取视图坐标系下的选区矩形 */
    private val touchViewRect: RectF
        get() = RectF(0f, 0f, selectionRect.width(), selectionRect.height())

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
     * 手指按下：判断触摸位置，决定进入 DRAG / RESIZE / 点击确认/关闭 模式。
     * 触摸坐标 (x,y) 为视图坐标系，与 viewRect 同坐标系。
     */
    private fun handleTouchDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y
        lastValidX = x
        lastValidY = y

        val vr = touchViewRect

        // 优先级 1：点击 X 关闭按钮
        if (RectUtil.hitCloseButton(x, y, vr, closeButtonRadius * 2f, 0f, touchSlop)) {
            // 点击关闭 → 取消选题
            onDismiss?.invoke()
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 2：点击"开始搜题"按钮 → 进入连续搜题模式
        if (hitConfirmButton(x, y)) {
            val rect = Rect(
                selectionRect.left.toInt(),
                selectionRect.top.toInt(),
                selectionRect.right.toInt(),
                selectionRect.bottom.toInt()
            )
            onStartContinuousSearch?.invoke(rect)
            touchMode = TouchMode.NONE
            return
        }

        // 优先级 3：点击缩放控制点
        if (RectUtil.hitResizeHandle(x, y, vr, resizeHandleSize, touchSlop)) {
            touchMode = TouchMode.RESIZE
            resizeStartRect.set(selectionRect)
            // 缩放锚点为左上角（固定不动）
            resizeAnchorX = selectionRect.left
            resizeAnchorY = selectionRect.top
            return
        }

        // 优先级 4：点击选区内部 → 拖动
        if (vr.contains(x, y)) {
            touchMode = TouchMode.DRAG
            dragStartRect.set(selectionRect)
            return
        }

        // 优先级 5：点击选区外 → 取消选题
        onDismiss?.invoke()
        touchMode = TouchMode.NONE
    }

    /**
     * 手指移动：根据当前模式计算选区变化。
     * 包含防抖逻辑：触摸死区 5px，防止微颤导致选区跳动。
     */
    private fun handleTouchMove(x: Float, y: Float) {
        // 防抖：移动距离小于死区阈值时忽略
        val moveDx = kotlin.math.abs(x - lastValidX)
        val moveDy = kotlin.math.abs(y - lastValidY)
        if (moveDx < touchDeadZone && moveDy < touchDeadZone) {
            return
        }
        lastValidX = x
        lastValidY = y

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
                Log.d("【SELECT_LOG】", "handleTouchMove DRAG: rect=(${selectionRect.left.toInt()},${selectionRect.top.toInt()},${selectionRect.right.toInt()},${selectionRect.bottom.toInt()})")
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
                // 选区尺寸变化后请求重新测量视图
                requestLayout()
                Log.d("【SELECT_LOG】", "handleTouchMove RESIZE: rect=(${selectionRect.left.toInt()},${selectionRect.top.toInt()},${selectionRect.right.toInt()},${selectionRect.bottom.toInt()})")
            }

            TouchMode.NONE -> { /* 不处理 */ }
        }
    }

    /**
     * 手指抬起：仅重置触摸模式，不自动确认选区。
     * 用户需点击「开始搜题」按钮来启动搜题。
     * 在连续搜题模式下，拖拽/缩放完成后通知选区变化。
     * 在调整模式下，自动隐藏选区框并触发调整完成回调。
     */
    private fun handleTouchUp(x: Float, y: Float) {
        if (touchMode != TouchMode.NONE) {
            val rect = Rect(
                selectionRect.left.toInt(),
                selectionRect.top.toInt(),
                selectionRect.right.toInt(),
                selectionRect.bottom.toInt()
            )
            Log.d("【SELECT_LOG】", "handleTouchUp: touchMode=$touchMode rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) isAdjustmentMode=$isAdjustmentMode isContinuousMode=$isContinuousMode")

            // 拖拽/缩放结束后，更新窗口位置以匹配选区新位置
            updateWindowPosition()
            Log.d("【SELECT_LOG】", "handleTouchUp: 窗口位置已更新")

            // 保存最新选区到持久化存储（记忆选区位置）
            onSaveRect?.invoke(rect)
            Log.d("【SELECT_LOG】", "handleTouchUp: 已调用 onSaveRect 保存选区")

            if (isAdjustmentMode) {
                // 调整模式：手指抬起后自动隐藏，触发调整完成回调
                Log.d("【SELECT_LOG】", "handleTouchUp: 触发 onAdjustmentComplete")
                onAdjustmentComplete?.invoke(rect)
            } else if (isContinuousMode) {
                // 连续搜题模式：拖拽/缩放完成后触发重新搜题
                Log.d("【SELECT_LOG】", "handleTouchUp: 触发 onSelectionChanged")
                onSelectionChanged?.invoke(rect)
            }
        }
        touchMode = TouchMode.NONE
    }

    /**
     * 判断触摸点是否点击了确认选区按钮（视图坐标系）。
     */
    private fun hitConfirmButton(x: Float, y: Float): Boolean {
        val r = touchViewRect
        val btnCenterX = r.centerX()
        val btnTop = r.bottom + confirmButtonMargin
        val btnLeft = btnCenterX - confirmButtonWidth / 2f - touchSlop
        val btnRight = btnCenterX + confirmButtonWidth / 2f + touchSlop
        val btnBottom = btnTop + confirmButtonHeight + touchSlop
        return x >= btnLeft && x <= btnRight && y >= btnTop && y <= btnBottom
    }

    /**
     * 将选区矩形约束在屏幕边界内。
     */
    private fun clampToScreen() {
        val bounds = RectF(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
        selectionRect = RectUtil.clampToBounds(selectionRect, bounds)
    }

    // ==================== 窗口附加/移除 ====================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedCallbackReceived = true
        Log.d("【SELECT_LOG】", "onAttachedToWindow: 视图已附加到窗口, selectionRect=(${selectionRect.left.toInt()},${selectionRect.top.toInt()},${selectionRect.right.toInt()},${selectionRect.bottom.toInt()})")
        onWindowAttached?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.w("【SELECT_LOG】", "onDetachedFromWindow: 视图已从窗口分离 (可能被系统拒绝)")
        onWindowDetached?.invoke()
    }

    /**
     * 通过 WindowManager 将悬浮窗附加到屏幕上。
     *
     * 窗口尺寸 WRAP_CONTENT，仅包裹选区 + 控制按钮。
     * 窗口位置 = 选区左上角，允许用户拖动窗口整体移动。
     */
    fun attachToWindow() {
        if (isAttached) return

        // 获取屏幕尺寸
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 初始化选区
        initSelectionRect()
        Log.d("【SELECT_LOG】", "attachToWindow: initSelectionRect 完成, selectionRect=(${selectionRect.left.toInt()},${selectionRect.top.toInt()},${selectionRect.right.toInt()},${selectionRect.bottom.toInt()})")

        // 标记：在 onAttachedToWindow 中设为 true，用于延迟检查
        attachedCallbackReceived = false

        // 计算窗口偏移（视图原点 = 选区左上角）
        windowOffsetX = selectionRect.left.toInt()
        windowOffsetY = selectionRect.top.toInt()

        val params = WindowManager.LayoutParams().apply {
            windowParams = this
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // 关键 Flag：不拦截底层页面触控，不获取焦点
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            // WRAP_CONTENT：仅包裹选区 + 控制按钮，不铺满全屏
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = windowOffsetX
            y = windowOffsetY

            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        try {
            windowManager.addView(this, params)
            isAttached = true
            Log.d("【SELECT_LOG】", "attachToWindow: addView 成功, windowPos=(${params.x},${params.y}), screen=${screenWidth}x${screenHeight}")
            invalidate()
            postInvalidate()

            // 延迟检视：1 秒后检查 onAttachedToWindow 是否触发
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAttached && !attachedCallbackReceived) {
                    Log.w("【SELECT_LOG】", "attachToWindow: 延迟检查发现 onAttachedToWindow 未触发，系统可能异步拒绝了窗口，尝试重建")
                    try { windowManager.removeView(this@FloatSelectOverlay) } catch (_: Exception) {}
                    isAttached = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        doAttach(params)
                    }, 100)
                } else if (isAttached && attachedCallbackReceived) {
                    Log.d("【SELECT_LOG】", "attachToWindow: 延迟检查通过，onAttachedToWindow 已正常触发")
                }
            }, 1000)
        } catch (e: SecurityException) {
            Log.e("【SELECT_LOG】", "attachToWindow: addView 失败(SecurityException): ${e.message}")
            isAttached = false
        } catch (e: Exception) {
            Log.e("【SELECT_LOG】", "attachToWindow: addView 异常: ${e.message}", e)
            isAttached = false
        }
    }

    /** onAttachedToWindow 是否已被回调（用于延迟检查） */
    private var attachedCallbackReceived = false

    /**
     * 实际执行 addView 的封装，供延迟重试使用。
     */
    private fun doAttach(params: WindowManager.LayoutParams) {
        if (isAttached) return
        attachedCallbackReceived = false
        try {
            windowManager.addView(this, params)
            isAttached = true
            Log.d("【SELECT_LOG】", "doAttach: 重试 addView 成功")
            invalidate()
            postInvalidate()

            // 二次延迟检查
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAttached && !attachedCallbackReceived) {
                    Log.e("【SELECT_LOG】", "doAttach: 重试后 onAttachedToWindow 仍未触发，叠加窗口可能被系统阻止")
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e("【SELECT_LOG】", "doAttach: 重试 addView 失败: ${e.message}", e)
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
     * 拖拽/缩放手势结束后，更新窗口位置以匹配选区新位置。
     * 窗口位置始终等于选区左上角（selectionRect.left, selectionRect.top）。
     */
    private fun updateWindowPosition() {
        val params = windowParams ?: return
        val newX = selectionRect.left.toInt()
        val newY = selectionRect.top.toInt()
        if (params.x != newX || params.y != newY) {
            params.x = newX
            params.y = newY
            windowOffsetX = newX
            windowOffsetY = newY
            try {
                windowManager.updateViewLayout(this, params)
                Log.d("【SELECT_LOG】", "updateWindowPosition: 窗口位置已更新到 ($newX, $newY)")
            } catch (e: Exception) {
                Log.e("【SELECT_LOG】", "updateWindowPosition 异常: ${e.message}", e)
            }
        }
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