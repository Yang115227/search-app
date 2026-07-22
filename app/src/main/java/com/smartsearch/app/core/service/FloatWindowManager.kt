package com.smartsearch.app.core.service

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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

    // ── 连续搜题定时器 ──
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

    /** 连续搜题轮询间隔（毫秒） */
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
            // 点击 X 关闭按钮 → 销毁
            onDismiss = {
                destroyAll()
            }

            // 点击"开始搜题" → 进入连续搜题模式
            onStartContinuousSearch = { rect ->
                // 保存选区
                lastSelectionRect = rect
                // 触发首次搜索
                this@FloatWindowManager.onRectSelected?.invoke(rect)
                // 进入连续搜题模式（隐藏选区框，显示底部答案弹窗）
                startContinuousSearch(ctx)
            }

            // 连续搜题模式下，选区变化触发重新搜题
            onSelectionChanged = { rect ->
                if (currentState == FloatWindowState.CONTINUOUS_SEARCHING) {
                    lastSelectionRect = rect
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
     * 3. 启动 3 秒轮询定时器
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

        // 启动 3 秒轮询
        searchHandler.removeCallbacks(searchRunnable)
        searchHandler.postDelayed(searchRunnable, CONTINUOUS_SEARCH_INTERVAL_MS)
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
                hideSelectOverlay()
            }

            // 拖拽/缩放时更新选区
            onSelectionChanged = { rect ->
                lastSelectionRect = rect
            }

            // 调整完成（手指抬起）→ 自动隐藏，触发搜索（仅连续搜题模式）
            onAdjustmentComplete = { rect ->
                lastSelectionRect = rect
                this@FloatWindowManager.onContinuousSearch?.invoke(rect)
                hideSelectOverlay()
            }

            // 非调整模式（ANSWERING）：点击「开始搜题」进入连续搜题
            onStartContinuousSearch = { rect ->
                lastSelectionRect = rect
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
                currentState = FloatWindowState.IDLE
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
     * 调用前需确保录屏服务已启动（通过 [ScreenCaptureService.startWithProjection]）。
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
     */
    fun destroyAll() {
        stopContinuousSearch()
        destroySelectOverlay()
        destroyAnswerWindow()
        onRectSelected = null
        onAnswerDismissed = null
        onContinuousSearch = null
        lastSelectionRect = null
        currentSearchMode = SearchMode.ACCESSIBILITY
        currentState = FloatWindowState.IDLE
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