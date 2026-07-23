package com.smartsearch.app.core.service

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.smartsearch.app.feature.search.capture.ScreenCaptureService
import com.smartsearch.app.feature.search.floatview.AnswerFloatWindow
import com.smartsearch.app.feature.search.floatview.FloatSelectOverlay

/**
 * 悬浮窗统一管理器 —— 管理 FloatSelectOverlay 与 AnswerFloatWindow 两个悬浮窗的
 * 生命周期、切换逻辑、回调缓存。
 *
 * # 生命周期状态机
 * ```
 *   IDLE ──showSelectOverlay()──▶ SELECTING
 *   SELECTING ──点击"开始搜题"──▶ CONTINUOUS_SEARCHING  (隐藏 Overlay，显示底部 AnswerWindow)
 *   CONTINUOUS_SEARCHING ──点击「选区」──▶ 重新显示 Overlay(调整模式)
 *   调整模式 ──手指抬起──▶ 自动隐藏 Overlay，继续轮询 ──▶ CONTINUOUS_SEARCHING
 *   CONTINUOUS_SEARCHING ──点击关闭──▶ IDLE              (销毁一切，回到悬浮球)
 *   SELECTING ──点击X关闭──▶ IDLE                        (销毁一切)
 * ```
 *
 * # 连续搜题模式
 * - 点击"开始搜题"后进入连续搜题模式，选区框自动隐藏，底部答案弹窗常驻
 * - 每 3 秒自动轮询识别一次
 * - 点击答案弹窗的「选区」按钮重新唤起选区框
 * - 调整选区后手指抬起，选区框自动隐藏，继续轮询
 * - 点击关闭按钮退出连续搜题模式，回到悬浮球
 */
object FloatWindowManager {

    /**
     * 获取状态栏高度（像素）。
     */
    fun getStatusBarHeight(context: Context): Int {
        return try {
            val activity = context as? Activity
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val insets = activity.window.decorView.rootWindowInsets
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(WindowInsets.Type.statusBars()).top
                    } else {
                        @Suppress("DEPRECATION")
                        insets.systemWindowInsetTop
                    }
                } else {
                    getStatusBarHeightFromResources(context)
                }
            } else {
                getStatusBarHeightFromResources(context)
            }
        } catch (e: Exception) {
            getStatusBarHeightFromResources(context)
        }
    }

    private fun getStatusBarHeightFromResources(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    /**
     * 获取导航栏高度（像素）。
     */
    fun getNavigationBarHeight(context: Context): Int {
        return try {
            val activity = context as? Activity
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = activity.window.decorView.rootWindowInsets
                if (insets != null) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    0
                }
            } else {
                val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 将屏幕坐标转换为应用可视区域坐标（扣除状态栏、导航栏）。
     */
    fun screenToAppRect(context: Context, screenRect: Rect): Rect {
        val statusBarHeight = getStatusBarHeight(context)
        val navBarHeight = getNavigationBarHeight(context)
        return Rect(
            screenRect.left,
            (screenRect.top - statusBarHeight).coerceAtLeast(0),
            screenRect.right,
            (screenRect.bottom - navBarHeight).coerceAtMost(
                context.resources.displayMetrics.heightPixels - navBarHeight
            )
        )
    }

    /**
     * 将应用可视区域坐标转换为屏幕坐标（加上状态栏、导航栏）。
     */
    fun appToScreenRect(context: Context, appRect: Rect): Rect {
        val statusBarHeight = getStatusBarHeight(context)
        val navBarHeight = getNavigationBarHeight(context)
        val screenHeight = context.resources.displayMetrics.heightPixels
        return Rect(
            appRect.left,
            (appRect.top + statusBarHeight).coerceAtMost(screenHeight - navBarHeight),
            appRect.right,
            (appRect.bottom + statusBarHeight).coerceAtMost(screenHeight)
        )
    }

    /**
     * 选区持久化存储（SharedPreferences）
     * - 三组独立前缀：access_ / record_ / camera_，彻底隔离
     * - 横竖屏分开存储（orientation后缀）
     * - 存取时自动做坐标换算（扣除/加上状态栏、导航栏高度）
     * - 使用 commit() 同步落盘，防止进程被杀丢失
     * - 加载后自动做屏幕边界裁剪适配
     */
    object SelectionPrefs {
        private const val PREFS_NAME = "float_selection_v3"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_RIGHT = "right"
        private const val KEY_BOTTOM = "bottom"

        private fun getModeKey(mode: SearchMode): String = when (mode) {
            SearchMode.ACCESSIBILITY -> "access"
            SearchMode.SCREEN_CAPTURE -> "record"
            SearchMode.CAMERA -> "camera"
        }

        /** 获取横竖屏后缀：横屏返回 "_land"，竖屏返回空字符串 */
        private fun getOrientationSuffix(context: Context): String {
            return try {
                val orientation = context.resources.configuration.orientation
                if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "_land" else ""
            } catch (_: Exception) { "" }
        }

        /**
         * 保存选区（自动扣除状态栏/导航栏，转为应用可视区域坐标）。
         * 使用 commit() 同步落盘，确保数据立即写入。
         */
        fun save(context: Context, rawRect: Rect, mode: SearchMode? = null) {
            try {
                val modeKey = getModeKey(mode ?: currentSearchMode)
                val orientationSuffix = getOrientationSuffix(context)
                // 扣除状态栏、导航栏高度，存储为应用可视区域坐标
                val appRect = screenToAppRect(context, rawRect)
                Log.d("SelectionPrefs", "save: mode=$modeKey$orientationSuffix appRect=$appRect raw=$rawRect")
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("${modeKey}${orientationSuffix}_$KEY_LEFT", appRect.left)
                    .putInt("${modeKey}${orientationSuffix}_$KEY_TOP", appRect.top)
                    .putInt("${modeKey}${orientationSuffix}_$KEY_RIGHT", appRect.right)
                    .putInt("${modeKey}${orientationSuffix}_$KEY_BOTTOM", appRect.bottom)
                    .commit() // 同步落盘，防止进程被杀丢失
            } catch (e: Exception) {
                Log.e("SelectionPrefs", "保存选区失败: ${e.message}", e)
            }
        }

        /**
         * 加载选区（自动加上状态栏/导航栏，转为屏幕坐标，并做边界裁剪）。
         * 如果历史选区超出当前屏幕尺寸，自动缩小至合法范围。
         * 无历史记录返回 null（调用方加载默认选区）。
         */
        fun load(context: Context, mode: SearchMode? = null): Rect? {
            try {
                val modeKey = getModeKey(mode ?: currentSearchMode)
                val orientationSuffix = getOrientationSuffix(context)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val left = prefs.getInt("${modeKey}${orientationSuffix}_$KEY_LEFT", -1)
                val top = prefs.getInt("${modeKey}${orientationSuffix}_$KEY_TOP", -1)
                val right = prefs.getInt("${modeKey}${orientationSuffix}_$KEY_RIGHT", -1)
                val bottom = prefs.getInt("${modeKey}${orientationSuffix}_$KEY_BOTTOM", -1)
                if (left < 0 || top < 0 || right < 0 || bottom < 0) {
                    // 当前方向无数据，尝试兼容旧版（无方向后缀）
                    val leftFallback = prefs.getInt("${modeKey}_$KEY_LEFT", -1)
                    val topFallback = prefs.getInt("${modeKey}_$KEY_TOP", -1)
                    val rightFallback = prefs.getInt("${modeKey}_$KEY_RIGHT", -1)
                    val bottomFallback = prefs.getInt("${modeKey}_$KEY_BOTTOM", -1)
                    if (leftFallback < 0 || topFallback < 0 || rightFallback < 0 || bottomFallback < 0) {
                        Log.d("SelectionPrefs", "load: mode=$modeKey$orientationSuffix 无历史记录")
                        return null
                    }
                    val appRect = Rect(leftFallback, topFallback, rightFallback, bottomFallback)
                    val clampedRect = clampToScreenBounds(context, appRect)
                    Log.d("SelectionPrefs", "load(legacy): mode=$modeKey appRect=$appRect clamped=$clampedRect")
                    return clampedRect
                }
                val appRect = Rect(left, top, right, bottom)
                val clampedRect = clampToScreenBounds(context, appRect)
                Log.d("SelectionPrefs", "load: mode=$modeKey$orientationSuffix appRect=$appRect clamped=$clampedRect")
                return clampedRect
            } catch (e: Exception) {
                Log.e("SelectionPrefs", "加载选区失败: ${e.message}", e)
                return null
            }
        }

        /**
         * 将应用可视区域坐标转为屏幕坐标，并裁剪到屏幕边界内。
         * 如果历史选区超出当前屏幕尺寸，自动缩小至合法范围。
         */
        private fun clampToScreenBounds(context: Context, appRect: Rect): Rect {
            val statusBarHeight = getStatusBarHeight(context)
            val navBarHeight = getNavigationBarHeight(context)
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            // 转为屏幕坐标
            val screenRect = Rect(
                appRect.left,
                appRect.top + statusBarHeight,
                appRect.right,
                appRect.bottom + statusBarHeight
            )
            // 边界裁剪，确保最小尺寸
            val minW = (screenW * 0.15f).toInt().coerceIn(80, 200)
            val minH = (screenH * 0.10f).toInt().coerceIn(60, 150)
            var left = screenRect.left.coerceIn(0, screenW - minW)
            var top = screenRect.top.coerceIn(0, screenH - minH)
            var right = screenRect.right.coerceIn(left + minW, screenW)
            var bottom = screenRect.bottom.coerceIn(top + minH, screenH)
            // 二次回拉：保持宽高不变整体移动
            if (right > screenW) { val w = right - left; right = screenW; left = (right - w).coerceAtLeast(0) }
            if (bottom > screenH) { val h = bottom - top; bottom = screenH; top = (bottom - h).coerceAtLeast(0) }
            if (left < 0) { right -= left; left = 0 }
            if (top < 0) { bottom -= top; top = 0 }
            // 最终确保最小尺寸
            if (right - left < minW) { right = left + minW; if (right > screenW) { left = screenW - minW; right = screenW } }
            if (bottom - top < minH) { bottom = top + minH; if (bottom > screenH) { top = screenH - minH; bottom = screenH } }
            return Rect(left, top, right, bottom)
        }

        fun clear(context: Context, mode: SearchMode? = null) {
            try {
                val modeKey = getModeKey(mode ?: currentSearchMode)
                val orientationSuffix = getOrientationSuffix(context)
                val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                editor.remove("${modeKey}${orientationSuffix}_$KEY_LEFT")
                editor.remove("${modeKey}${orientationSuffix}_$KEY_TOP")
                editor.remove("${modeKey}${orientationSuffix}_$KEY_RIGHT")
                editor.remove("${modeKey}${orientationSuffix}_$KEY_BOTTOM")
                editor.commit() // 同步落盘
            } catch (e: Exception) {
                Log.e("SelectionPrefs", "清除选区失败: ${e.message}", e)
            }
        }
    }

    /** 当前悬浮窗状态 */
    private var currentState = FloatWindowState.IDLE

    // ── 悬浮窗实例 ──
    private var selectOverlay: FloatSelectOverlay? = null
    private var answerWindow: AnswerFloatWindow? = null

    // ── 回调缓存 ──
    /** 选区确认回调，选题框用户点击"开始搜题"后触发 */
    private var onRectSelected: ((Rect) -> Unit)? = null

    /** 答案弹窗关闭回调 */
    private var onAnswerDismissed: (() -> Unit)? = null

    /** 连续搜题模式下的搜索回调（每次轮询时触发） */
    private var onContinuousSearch: ((Rect) -> Unit)? = null

    /** 上一次保存的选区矩形（用于重新唤起选区框时恢复位置） */
    private var lastSelectionRect: Rect? = null

    // ── 上下文 ──
    private var appContext: Context? = null

    // ── 连续搜题定时器（仅无障碍模式使用） ──
    // 录屏模式使用 ScreenCaptureService 内置的 ImageReader 连续采集（200ms 节流），无需此定时器
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = object : Runnable {
        override fun run() {
            if (currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
                // 使用上次保存的选区触发搜索
                val rect = lastSelectionRect
                if (rect != null) {
                    onContinuousSearch?.invoke(rect)
                }
                // 继续定时轮询
                searchHandler.postDelayed(this, CONTINUOUS_SEARCH_INTERVAL_MS)
            }
        }
    }

    /** 连续搜题轮询间隔（毫秒），仅无障碍模式使用 */
    private const val CONTINUOUS_SEARCH_INTERVAL_MS = 3000L

    // ==================== 状态枚举 ====================

    /**
     * 悬浮窗状态枚举。
     * - [IDLE]：所有悬浮窗均已销毁
     * - [SELECTING]：选题框正在显示
     * - [ANSWERING]：答案弹窗正在显示
     * - [CONTINUOUS_SEARCHING]：连续搜题模式（选区框隐藏，底部答案弹窗常驻）
     */
    enum class FloatWindowState {
        IDLE,
        SELECTING,
        ANSWERING,
        CONTINUOUS_SEARCHING
    }

    /**
     * 当前搜题模式。
     * - [ACCESSIBILITY]：无障碍模式
     * - [SCREEN_CAPTURE]：录屏模式
     */
    enum class SearchMode {
        ACCESSIBILITY,
        SCREEN_CAPTURE,
        CAMERA
    }

    /** 当前搜题模式，默认无障碍 */
    private var currentSearchMode = SearchMode.ACCESSIBILITY

    // ==================== 初始化 ====================

    /**
     * 初始化管理器，必须在 [android.app.Application.onCreate] 中调用。
     * @param context Application 上下文
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ==================== 显示选题框 ====================

    /**
     * 显示选题框 [FloatSelectOverlay]。
     *
     * 如果当前已有答案弹窗，会先销毁再显示选题框。
     * 如果当前已有选题框，不会重复创建。
     *
     * @param context 上下文（建议传 Activity 或 Service）
     * @param onSearch 搜索回调，用户点击"开始搜题"后触发，进入连续搜题模式后每次轮询也会触发
     */
    fun showSelectOverlay(context: Context, onSearch: (Rect) -> Unit) {
        val ctx = context.applicationContext
        // 缓存回调
        this.onRectSelected = onSearch
        this.onContinuousSearch = onSearch

        // 如果已有答案弹窗，先销毁
        if (currentState == FloatWindowState.ANSWERING) {
            destroyAnswerWindow()
        }

        // 如果已有选题框，不重复创建
        if (currentState == FloatWindowState.SELECTING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING) return

        selectOverlay = FloatSelectOverlay(ctx).apply {
            // 恢复上次选区位置：先从内存加载，内存没有则从持久化存储加载（按当前模式分键）
            var savedRect = this@FloatWindowManager.lastSelectionRect
            if (savedRect == null) {
                savedRect = SelectionPrefs.load(ctx, currentSearchMode)
                if (savedRect != null) {
                    this@FloatWindowManager.lastSelectionRect = savedRect
                }
            }
            if (savedRect != null) {
                setSelectionRect(savedRect)
            }

            // 点击 X 关闭按钮 → 销毁
            onDismiss = {
                destroyAll()
            }

            // 点击"开始搜题" → 进入连续搜题模式
            onStartContinuousSearch = { rect ->
                // 保存选区（内存 + 持久化，按模式分键）
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
                // 触发首次搜索
                this@FloatWindowManager.onRectSelected?.invoke(rect)
                // 进入连续搜题模式（隐藏选区框，显示底部答案弹窗）
                startContinuousSearch(ctx)
            }

            // 选区变化时立即落盘保存（记忆功能）
            onSelectionChanged = { rect ->
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
                if (currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
                    this@FloatWindowManager.onContinuousSearch?.invoke(rect)
                }
            }

            // 附加到窗口
            attachToWindow()
        }

        currentState = FloatWindowState.SELECTING
    }

    // ==================== 连续搜题模式 ====================

    /**
     * 启动连续搜题模式。
     *
     * 1. 隐藏选区框（从 WindowManager 移除）
     * 2. 显示底部答案弹窗
     * 3. 启动轮询定时器（仅无障碍模式，录屏模式使用 ScreenCaptureService 内置采集）
     */
    private fun startContinuousSearch(context: Context) {
        currentState = FloatWindowState.CONTINUOUS_SEARCHING

        // 隐藏选区框
        hideSelectOverlay()

        // 显示底部答案弹窗（带"等待识别结果..."提示）
        val answerCtx = appContext ?: context
        showAnswerWindowInternal(
            answerCtx,
            answer = "",
            explanation = "等待识别结果..."
        )

        // 仅无障碍模式启动 3 秒轮询
        // 录屏模式使用 ScreenCaptureService 内置的 ImageReader 连续采集（200ms 节流）
        if (currentSearchMode != SearchMode.SCREEN_CAPTURE) {
            searchHandler.removeCallbacks(searchRunnable)
            searchHandler.postDelayed(searchRunnable, CONTINUOUS_SEARCH_INTERVAL_MS)
        }
    }

    /**
     * 隐藏选区框（从 WindowManager 移除，但保留状态）。
     * 选区位置已保存在 lastSelectionRect 中。
     */
    private fun hideSelectOverlay() {
        selectOverlay?.detachFromWindow()
        selectOverlay = null
    }

    /**
     * 重新唤起选区框（调整模式或重新选题）。
     * 在连续搜题模式或答案显示模式下，点击答案弹窗的「选区」按钮时调用。
     * - 连续搜题模式：调整模式，手指抬起自动隐藏，继续轮询
     * - 答案显示模式：普通模式，显示「开始搜题」按钮，点击后进入连续搜题
     */
    fun showOverlayForAdjustment(context: Context) {
        if (currentState != FloatWindowState.CONTINUOUS_SEARCHING &&
            currentState != FloatWindowState.ANSWERING) return

        val ctx = context.applicationContext
        val isContinuous = currentState == FloatWindowState.CONTINUOUS_SEARCHING

        // 录屏模式下暂停连续采集（选区调整中）
        if (isContinuous && currentSearchMode == SearchMode.SCREEN_CAPTURE) {
            ScreenCaptureService.getInstance()?.pauseContinuousSearch()
        }

        selectOverlay = FloatSelectOverlay(ctx).apply {
            // 恢复上次选区位置
            val savedRect = this@FloatWindowManager.lastSelectionRect
            if (savedRect != null) {
                setSelectionRect(savedRect)
            }

            // 连续搜题模式：调整模式，手指抬起自动隐藏
            isAdjustmentMode = isContinuous
            isContinuousMode = isContinuous

            // 点击 X 关闭按钮 → 回到之前的状态
            onDismiss = {
                // 录屏模式下恢复采集
                if (isContinuous && currentSearchMode == SearchMode.SCREEN_CAPTURE) {
                    ScreenCaptureService.getInstance()?.resumeContinuousSearch()
                }
                hideSelectOverlay()
            }

            // 拖拽/缩放时立即落盘更新选区（内存 + 持久化，按模式分键）
            onSelectionChanged = { rect ->
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
            }

            // 调整完成（手指抬起）→ 自动隐藏，触发搜索（仅连续搜题模式）
            onAdjustmentComplete = { rect ->
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
                // 录屏模式下更新选区并恢复采集
                if (isContinuous && currentSearchMode == SearchMode.SCREEN_CAPTURE) {
                    ScreenCaptureService.getInstance()?.apply {
                        updateSelectionRect(rect)
                        resumeContinuousSearch()
                    }
                }
                this@FloatWindowManager.onContinuousSearch?.invoke(rect)
                hideSelectOverlay()
            }

            // 非调整模式（ANSWERING）：点击「开始搜题」进入连续搜题
            onStartContinuousSearch = { rect ->
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
                this@FloatWindowManager.onRectSelected?.invoke(rect)
                startContinuousSearch(ctx)
            }

            // 附加到窗口
            attachToWindow()
        }
    }

    /**
     * 停止连续搜题模式，回到 IDLE。
     * 不销毁底部答案弹窗（由外部调用者决定）。
     */
    private fun stopContinuousSearch() {
        searchHandler.removeCallbacks(searchRunnable)
        hideSelectOverlay()
    }

    // ==================== 显示答案弹窗 ====================

    /**
     * 显示答案弹窗（外部公开 API）。
     *
     * 在连续搜题模式下，只更新答案内容，不销毁/重建弹窗。
     *
     * @param context 上下文
     * @param answer 答案文本
     * @param explanation 解析文本（可选）
     * @param onDismissed 弹窗关闭回调
     */
    fun showAnswerWindow(
        context: Context,
        answer: String,
        explanation: String = "",
        onDismissed: (() -> Unit)? = null
    ) {
        val ctx = context.applicationContext
        this.onAnswerDismissed = onDismissed

        // 连续搜题模式下：只更新底部答案弹窗的内容
        if (currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
            answerWindow?.setContent(answer, explanation)
            return
        }

        // 普通模式：先销毁选题框
        if (currentState == FloatWindowState.SELECTING) {
            destroySelectOverlay()
        }

        // 如果已有答案弹窗，先销毁旧的
        if (currentState == FloatWindowState.ANSWERING) {
            destroyAnswerWindow()
        }

        showAnswerWindowInternal(ctx, answer, explanation)
        currentState = FloatWindowState.ANSWERING
    }

    /**
     * 内部：创建并显示底部答案弹窗。
     */
    private fun showAnswerWindowInternal(
        ctx: Context,
        answer: String,
        explanation: String
    ) {
        destroyAnswerWindow()

        answerWindow = AnswerFloatWindow(ctx).apply {
            setContent(answer, explanation)

            // 点击关闭按钮 → 退出连续搜题模式，回到悬浮球
            onDismiss = {
                stopContinuousSearch()
                destroyAnswerWindow()
                this@FloatWindowManager.onAnswerDismissed?.invoke()
                // 录屏模式下停止录屏服务
                if (currentSearchMode == SearchMode.SCREEN_CAPTURE || ScreenCaptureService.isRunning()) {
                    try {
                        val stopCtx = appContext ?: ctx
                        val stopIntent = android.content.Intent(stopCtx, ScreenCaptureService::class.java)
                        stopCtx.stopService(stopIntent)
                    } catch (e: Exception) {
                        ScreenCaptureService.getInstance()?.stopCapture()
                    }
                }
                currentState = FloatWindowState.IDLE
                currentSearchMode = SearchMode.ACCESSIBILITY
                // 重新显示当前模式的悬浮球
                FloatingWindowService.showBall()
            }

            // 点击「选区」按钮 → 重新唤起选区框
            onSelectAreaClick = {
                showOverlayForAdjustment(ctx)
            }

            attachToWindow()
        }
    }

    // ==================== 录屏模式入口 ====================

    /**
     * 以录屏模式显示选题框。
     *
     * 与无障碍模式的区别：选区确认后，坐标传递给 [ScreenCaptureService] 而非无障碍服务。
     * 录屏模式使用 ScreenCaptureService 内置的 ImageReader 连续采集（200ms 节流），
     * 选区确认后自动进入连续搜题模式，无需额外的 3 秒轮询定时器。
     *
     * @param context 上下文
     */
    fun showSelectOverlayForScreenCapture(context: Context) {
        currentSearchMode = SearchMode.SCREEN_CAPTURE

        showSelectOverlay(context) { rect ->
            // 选区确认 → 传递给录屏服务
            val captureService = ScreenCaptureService.getInstance()
            if (captureService != null) {
                captureService.updateSelectionRect(rect)
                // 已进入连续搜题模式，ScreenCaptureService 的 ImageReader 会自动持续采集
                // 只需触发一次初始截图，后续由 onImageAvailable 自动处理
                captureService.triggerCaptureOnce()
            } else {
                // 录屏服务未运行 → 提示用户
                showAnswerWindow(
                    context,
                    answer = "录屏服务未运行",
                    explanation = "录屏服务已停止，请重新授权录屏权限后重试。\n\n" +
                            "切换路径：\n" +
                            "悬浮球长按 → 切换录屏模式 → 授权录屏",
                    onDismissed = {
                        currentSearchMode = SearchMode.ACCESSIBILITY
                    }
                )
            }
        }
    }

    /**
     * 从无障碍模式切换到录屏模式，并显示选题框。
     *
     * @param context 上下文
     */
    fun switchToScreenCaptureMode(context: Context) {
        if (ScreenCaptureService.isRunning()) {
            currentSearchMode = SearchMode.SCREEN_CAPTURE
            showSelectOverlayForScreenCapture(context)
        } else {
            showAnswerWindow(
                context,
                answer = "切换到录屏模式",
                explanation = "需要先授权录屏权限才能使用录屏搜题。\n\n" +
                        "请通过以下方式启动：\n" +
                        "1. 悬浮球长按选择「录屏模式」\n" +
                        "2. 授权系统录屏权限\n" +
                        "3. 重新选题开始搜题",
                onDismissed = {
                    currentSearchMode = SearchMode.ACCESSIBILITY
                }
            )
        }
    }

    // ==================== 销毁逻辑 ====================

    /**
     * 仅销毁选题框。
     */
    private fun destroySelectOverlay() {
        selectOverlay?.detachFromWindow()
        selectOverlay = null
        if (currentState == FloatWindowState.SELECTING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING
        ) {
            currentState = FloatWindowState.IDLE
        }
    }

    /**
     * 仅销毁答案弹窗。
     */
    private fun destroyAnswerWindow() {
        answerWindow?.detachFromWindow()
        answerWindow = null
        if (currentState == FloatWindowState.ANSWERING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING
        ) {
            currentState = FloatWindowState.IDLE
        }
    }

    /**
     * 销毁所有悬浮窗，清除所有回调，回到 IDLE 状态。
     * 在录屏模式下，同时停止 ScreenCaptureService。
     */
    fun destroyAll() {
        stopContinuousSearch()
        destroySelectOverlay()
        destroyAnswerWindow()
        onRectSelected = null
        onAnswerDismissed = null
        onContinuousSearch = null
        lastSelectionRect = null

        // 先保存当前模式，再重置
        val wasScreenCapture = currentSearchMode == SearchMode.SCREEN_CAPTURE || ScreenCaptureService.isRunning()
        currentSearchMode = SearchMode.ACCESSIBILITY
        currentState = FloatWindowState.IDLE

        // 录屏模式下停止录屏服务
        if (wasScreenCapture) {
            try {
                val ctx = appContext
                if (ctx != null) {
                    val stopIntent = android.content.Intent(ctx, ScreenCaptureService::class.java)
                    ctx.stopService(stopIntent)
                } else {
                    // appContext 为 null 时直接调用 stopCapture
                    ScreenCaptureService.getInstance()?.stopCapture()
                }
            } catch (e: Exception) {
                ScreenCaptureService.getInstance()?.stopCapture()
            }
        }

        // 重新显示当前模式的悬浮球
        FloatingWindowService.showBall()
    }

    // ==================== 状态查询 ====================

    /** 获取当前悬浮窗状态 */
    fun getCurrentState(): FloatWindowState = currentState

    /** 选题框是否正在显示 */
    fun isSelecting(): Boolean = currentState == FloatWindowState.SELECTING

    /** 答案弹窗是否正在显示 */
    fun isAnswering(): Boolean = currentState == FloatWindowState.ANSWERING

    /** 是否处于连续搜题模式 */
    fun isContinuousSearching(): Boolean = currentState == FloatWindowState.CONTINUOUS_SEARCHING
}