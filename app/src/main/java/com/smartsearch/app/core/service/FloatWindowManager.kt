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
     * - 三组独立前缀：access_ / camera_，彻底隔离（录屏与无障碍共享 access_ 前缀）
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
            SearchMode.SCREEN_CAPTURE -> "access"
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
                val spKey = "${modeKey}${orientationSuffix}_"
                // 扣除状态栏、导航栏高度，存储为应用可视区域坐标
                val appRect = screenToAppRect(context, rawRect)
                Log.d("【SELECT_LOG】", "save: PREFS=$PREFS_NAME modeKey=$modeKey suffix=$orientationSuffix spKey=${spKey}left appRect=(${appRect.left},${appRect.top},${appRect.right},${appRect.bottom}) raw=(${rawRect.left},${rawRect.top},${rawRect.right},${rawRect.bottom})")
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("${spKey}$KEY_LEFT", appRect.left)
                    .putInt("${spKey}$KEY_TOP", appRect.top)
                    .putInt("${spKey}$KEY_RIGHT", appRect.right)
                    .putInt("${spKey}$KEY_BOTTOM", appRect.bottom)
                    .commit() // 同步落盘，防止进程被杀丢失
            } catch (e: Exception) {
                Log.e("【SELECT_LOG】", "save 异常: ${e.message}", e)
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
                val spKey = "${modeKey}${orientationSuffix}_"
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val left = prefs.getInt("${spKey}$KEY_LEFT", -1)
                val top = prefs.getInt("${spKey}$KEY_TOP", -1)
                val right = prefs.getInt("${spKey}$KEY_RIGHT", -1)
                val bottom = prefs.getInt("${spKey}$KEY_BOTTOM", -1)
                Log.d("【SELECT_LOG】", "load: PREFS=$PREFS_NAME spKey=${spKey}left 读取值=($left,$top,$right,$bottom)")
                if (left < 0 || top < 0 || right < 0 || bottom < 0) {
                    // 当前方向无数据，尝试兼容旧版（无方向后缀）
                    val legacyKey = "${modeKey}_"
                    val leftFallback = prefs.getInt("${legacyKey}$KEY_LEFT", -1)
                    val topFallback = prefs.getInt("${legacyKey}$KEY_TOP", -1)
                    val rightFallback = prefs.getInt("${legacyKey}$KEY_RIGHT", -1)
                    val bottomFallback = prefs.getInt("${legacyKey}$KEY_BOTTOM", -1)
                    Log.d("【SELECT_LOG】", "load: 当前方向无数据, 尝试旧版key=${legacyKey}left 值=($leftFallback,$topFallback,$rightFallback,$bottomFallback)")
                    if (leftFallback < 0 || topFallback < 0 || rightFallback < 0 || bottomFallback < 0) {
                        Log.d("【SELECT_LOG】", "load: 无历史记录, 返回null")
                        return null
                    }
                    val appRect = Rect(leftFallback, topFallback, rightFallback, bottomFallback)
                    val clampedRect = clampToScreenBounds(context, appRect)
                    Log.d("【SELECT_LOG】", "load(legacy): 返回 clamped=(${clampedRect.left},${clampedRect.top},${clampedRect.right},${clampedRect.bottom})")
                    return clampedRect
                }
                val appRect = Rect(left, top, right, bottom)
                val clampedRect = clampToScreenBounds(context, appRect)
                Log.d("【SELECT_LOG】", "load: 返回 clamped=(${clampedRect.left},${clampedRect.top},${clampedRect.right},${clampedRect.bottom})")
                return clampedRect
            } catch (e: Exception) {
                Log.e("【SELECT_LOG】", "load 异常: ${e.message}", e)
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

    /** 是否正在过渡中（防止并发重复创建，例如录屏授权回调+300ms延迟期间重复调用） */
    private var isTransitioning = false

    /**
     * 当前持有选区的模式（用于模式切换隔离检测）。
     * - null：无选区
     * - ACCESSIBILITY：无障碍模式持有
     * - SCREEN_CAPTURE：录屏模式持有
     * 当切换到新模式时，若 activeMode 与新模式不同，强制销毁旧选区再创建新实例。
     */
    private var activeMode: SearchMode? = null

    /** 销毁旧选区后等待 onDetachedFromWindow 的延迟（毫秒） */
    private val MODE_SWITCH_DELAY_MS = 100L

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
     * @param onOverlayAttached 选区悬浮窗已挂载到 WindowManager 的回调（onAttachedToWindow 触发）
     * @param onSearch 搜索回调，用户点击"开始搜题"后触发，进入连续搜题模式后每次轮询也会触发
     */
    fun showSelectOverlay(context: Context, onOverlayAttached: (() -> Unit)? = null, onSearch: (Rect) -> Unit) {
        val ctx = context.applicationContext
        Log.d("【SELECT_LOG】", "showSelectOverlay 入口: mode=${currentSearchMode} state=${currentState} activeMode=${activeMode}")

        // ── 模式切换检测：如果当前选区属于不同模式，强制销毁旧选区再创建新实例 ──
        if (activeMode != null && activeMode != currentSearchMode) {
            Log.d("【SELECT_LOG】", "showSelectOverlay: 检测到模式切换(${activeMode}→${currentSearchMode}), 强制销毁旧选区")
            forceDestroyCurrentOverlay()
        }

        // 缓存回调
        this.onRectSelected = onSearch
        this.onContinuousSearch = onSearch

        // 如果已有答案弹窗，先销毁
        if (currentState == FloatWindowState.ANSWERING) {
            destroyAnswerWindow()
        }

        // 如果已有选题框且模式匹配，不重复创建
        if (currentState == FloatWindowState.SELECTING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
            Log.d("【SELECT_LOG】", "showSelectOverlay: 已有选题框, 跳过创建")
            isTransitioning = false
            return
        }

        selectOverlay = FloatSelectOverlay(ctx).apply {
            // 恢复上次选区位置：先从内存加载，内存没有则从持久化存储加载（按当前模式分键）
            var savedRect = this@FloatWindowManager.lastSelectionRect
            Log.d("【SELECT_LOG】", "showSelectOverlay: 内存中 lastSelectionRect=${savedRect?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "null"}")
            if (savedRect == null) {
                savedRect = SelectionPrefs.load(ctx, currentSearchMode)
                Log.d("【SELECT_LOG】", "showSelectOverlay: SP加载结果=${savedRect?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "null"}")
                if (savedRect != null) {
                    this@FloatWindowManager.lastSelectionRect = savedRect
                }
            }
            // 点击 X 关闭按钮 → 销毁
            onDismiss = {
                destroyAll()
            }

            // 点击"开始搜题" → 进入连续搜题模式
            onStartContinuousSearch = { rect ->
                Log.d("【SELECT_LOG】", "showSelectOverlay onStartContinuousSearch: rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) mode=${currentSearchMode}")
                // 保存选区（内存 + 持久化，按模式分键）
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
                // 触发首次搜索
                this@FloatWindowManager.onRectSelected?.invoke(rect)
                // 进入连续搜题模式（隐藏选区框，显示底部答案弹窗）
                startContinuousSearch(ctx)
            }

            // 选区变化时更新内存缓存（不落盘，由 onSaveRect 在 TouchUp 时统一落盘）
            onSelectionChanged = { rect ->
                Log.d("【SELECT_LOG】", "showSelectOverlay onSelectionChanged: rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) mode=${currentSearchMode}")
                lastSelectionRect = rect
                // 注意：不在此处落盘，由 onSaveRect 在 TouchUp 时统一保存，减少频繁 SP 读写
                if (currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
                    this@FloatWindowManager.onContinuousSearch?.invoke(rect)
                }
            }

            // 手指拖动/缩放抬起后保存选区（记忆选区位置）
            onSaveRect = { rect ->
                Log.d("【SELECT_LOG】", "showSelectOverlay onSaveRect: rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) mode=${currentSearchMode}")
                lastSelectionRect = rect
                SelectionPrefs.save(ctx, rect, currentSearchMode)
            }

            // 窗口挂载回调（onAttachedToWindow 触发）
            onWindowAttached = {
                Log.d("【SELECT_LOG】", "showSelectOverlay: onWindowAttached 触发, 执行 onOverlayAttached")
                onOverlayAttached?.invoke()
            }

            // 第1步：先附加到窗口，确保屏幕尺寸已初始化
            attachToWindow()
            Log.d("【SELECT_LOG】", "showSelectOverlay: attachToWindow 完成, screen=${screenWidth}x${screenHeight}")

            // 第2步：再设置历史选区（如果有），此时 screenWidth/screenHeight 已就绪，setSelectionRect 不会出现 screen=0x0
            if (savedRect != null) {
                Log.d("【SELECT_LOG】", "showSelectOverlay: 调用 setSelectionRect 加载历史选区")
                setSelectionRect(savedRect)
            } else {
                Log.d("【SELECT_LOG】", "showSelectOverlay: 无历史选区, 使用默认居中选区")
            }
        }

        currentState = FloatWindowState.SELECTING
        activeMode = currentSearchMode // 记录当前选区归属模式
        isTransitioning = false
        Log.d("【SELECT_LOG】", "showSelectOverlay: 状态机 SELECTING, activeMode=${activeMode}, isTransitioning=false")
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
        Log.d("【SELECT_LOG】", "hideSelectOverlay: 移除选区, activeMode=${activeMode}→null")
        selectOverlay?.detachFromWindow()
        selectOverlay = null
        activeMode = null
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

        Log.d("【SELECT_LOG】", "showOverlayForAdjustment: 入口 state=${currentState} isContinuous=${isContinuous} activeMode=${activeMode} currentSearchMode=${currentSearchMode}")

        // ── 模式切换检测：如果当前 selectOverlay 属于不同模式，强制销毁旧选区 ──
        if (activeMode != null && activeMode != currentSearchMode) {
            Log.d("【SELECT_LOG】", "showOverlayForAdjustment: 检测到模式切换(${activeMode}→${currentSearchMode}), 强制销毁旧选区")
            forceDestroyCurrentOverlay()
        }

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
            Log.d("【SELECT_LOG】", "showOverlayForAdjustment: attachToWindow 完成, 设置 activeMode=${currentSearchMode}")
            activeMode = currentSearchMode
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
        // 状态机防并发：正在过渡中则跳过
        if (isTransitioning) {
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCapture: 正在过渡中, 跳过")
            return
        }

        // ── 模式切换检测：如果当前选区属于无障碍模式，强制销毁旧选区 ──
        if (activeMode != null && activeMode != SearchMode.SCREEN_CAPTURE) {
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCapture: 检测到模式切换(${activeMode}→SCREEN_CAPTURE), 强制销毁旧选区, state=${currentState}")
            forceDestroyCurrentOverlay()
        }

        // 如果已有录屏选题框，不重复创建
        if (currentState == FloatWindowState.SELECTING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCapture: 已有录屏选题框, 跳过, state=${currentState} activeMode=${activeMode}")
            return
        }

        isTransitioning = true
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
     * 以录屏模式显示选题框，并在选区挂载到 WindowManager 后通过回调通知调用方。
     *
     * 与 [showSelectOverlayForScreenCapture] 的区别：
     * - 此方法用于录屏授权回调流程（先创建选区 → 等待挂载 → 再启动 Service）
     * - 包含 1.5s 选区挂载超时检测
     *
     * 执行时序：
     * 1. 创建录屏独立选区 Overlay，附加到 WindowManager
     * 2. 等待 onAttachedToWindow 回调（1.5s 超时）
     * 3. 挂载成功 → [onOverlayReady] 回调，调用方在此启动前台 Service + 发送投影 Intent
     * 4. 挂载失败/超时 → [onOverlayFailed] 回调，调用方弹窗提示
     *
     * @param context 上下文
     * @param onOverlayReady 选区已挂载到 WindowManager 的回调（调用方在此启动前台 Service）
     * @param onOverlayFailed 选区挂载失败/超时的回调，参数为错误描述
     */
    fun showSelectOverlayForScreenCaptureWithAttach(
        context: Context,
        onOverlayReady: () -> Unit,
        onOverlayFailed: (String) -> Unit
    ) {
        // 状态机防并发
        if (isTransitioning) {
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCaptureWithAttach: 正在过渡中, 跳过")
            onOverlayFailed("正在过渡中，请稍后重试")
            return
        }

        // 模式切换检测
        if (activeMode != null && activeMode != SearchMode.SCREEN_CAPTURE) {
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCaptureWithAttach: 检测到模式切换(${activeMode}→SCREEN_CAPTURE), 强制销毁旧选区")
            forceDestroyCurrentOverlay()
        }

        isTransitioning = true
        currentSearchMode = SearchMode.SCREEN_CAPTURE

        // 1.5s 选区挂载超时检测
        val attachTimeout = Runnable {
            Log.e("【SELECT_LOG】", "showSelectOverlayForScreenCaptureWithAttach: 选区挂载超时(1.5s)")
            forceDestroyCurrentOverlay()
            isTransitioning = false
            onOverlayFailed("选区悬浮窗创建失败，请检查悬浮窗权限是否开启")
        }
        Handler(Looper.getMainLooper()).postDelayed(attachTimeout, 1500)

        showSelectOverlay(context, onOverlayAttached = {
            Handler(Looper.getMainLooper()).removeCallbacks(attachTimeout)
            Log.d("【SELECT_LOG】", "showSelectOverlayForScreenCaptureWithAttach: 选区已挂载到WindowManager, 通知调用方启动Service")
            onOverlayReady()
        }) { rect ->
            // 用户点击"开始搜题" → 选区确认
            val captureService = ScreenCaptureService.getInstance()
            if (captureService != null) {
                captureService.updateSelectionRect(rect)
                captureService.triggerCaptureOnce()
            } else {
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
     * 强制销毁当前选区（模式切换时调用）。
     * destroySelectOverlay 内部的 detachFromWindow → removeView 会同步触发
     * onDetachedFromWindow，无需额外等待。
     * 不重置 currentSearchMode，由调用方设置新模式。
     */
    private fun forceDestroyCurrentOverlay() {
        Log.d("【SELECT_LOG】", "forceDestroyCurrentOverlay: 强制销毁当前选区, activeMode=${activeMode}, state=${currentState}")
        stopContinuousSearch()
        destroySelectOverlay()
        // 清除当前模式回调，防止旧回调污染新模式
        onRectSelected = null
        onContinuousSearch = null
        lastSelectionRect = null
        Log.d("【SELECT_LOG】", "forceDestroyCurrentOverlay: 旧选区已销毁完毕, activeMode=${activeMode}, state=${currentState}")
    }

    /**
     * 仅销毁选题框。
     */
    private fun destroySelectOverlay() {
        Log.d("【SELECT_LOG】", "destroySelectOverlay: 销毁选区, activeMode=${activeMode}→null, state=${currentState}")
        selectOverlay?.detachFromWindow()
        selectOverlay = null
        activeMode = null
        if (currentState == FloatWindowState.SELECTING ||
            currentState == FloatWindowState.CONTINUOUS_SEARCHING
        ) {
            Log.d("【SELECT_LOG】", "destroySelectOverlay: 状态机 IDLE (was ${currentState})")
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
        Log.d("【SELECT_LOG】", "destroyAll: 销毁所有悬浮窗, activeMode=${activeMode}, state=${currentState}, mode=${currentSearchMode}")
        stopContinuousSearch()
        destroySelectOverlay()
        destroyAnswerWindow()
        onRectSelected = null
        onAnswerDismissed = null
        onContinuousSearch = null
        lastSelectionRect = null
        activeMode = null

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
