package com.smartsearch.app.core.service

import android.content.Context
import android.graphics.Rect
import com.smartsearch.app.feature.search.capture.ScreenCaptureService
import com.smartsearch.app.feature.search.floatview.AnswerFloatWindow
import com.smartsearch.app.feature.search.floatview.FloatSelectOverlay
import com.smartsearch.app.feature.search.floatview.FunctionPanelView

/**
 * 悬浮窗统一管理器 —— 管理 FloatSelectOverlay 与 AnswerFloatWindow 两个悬浮窗的
 * 生命周期、切换逻辑、回调缓存。
 *
 * # 生命周期状态机
 * ```
 *   IDLE ──showSelectOverlay()──▶ SELECTING
 *   SELECTING ──用户确认选区──▶ ANSWERING   (同时销毁 SelectOverlay)
 *   ANSWERING ──点击返回──▶ SELECTING       (销毁 AnswerWindow，重新打开 SelectOverlay)
 *   ANSWERING ──点击关闭──▶ IDLE            (销毁一切)
 *   SELECTING ──点击X关闭──▶ IDLE            (销毁一切)
 * ```
 *
 * # 使用方式
 * ```kotlin
 * // 初始化（在 Application.onCreate 中调用一次）
 * FloatWindowManager.init(context)
 *
 * // 显示选题框
 * FloatWindowManager.showSelectOverlay { rect -> /* 拿到选区坐标 */ }
 *
 * // 显示答案弹窗
 * FloatWindowManager.showAnswerWindow("答案内容", "解析内容")
 *
 * // 销毁所有
 * FloatWindowManager.destroyAll()
 * ```
 *
 * 兼容 Android 10 (API 29) ~ Android 14 (API 34)，无第三方依赖。
 */
object FloatWindowManager {

    /** 当前悬浮窗状态 */
    private var currentState = FloatWindowState.IDLE

    // ── 两个悬浮窗实例 ──
    private var selectOverlay: FloatSelectOverlay? = null
    private var answerWindow: AnswerFloatWindow? = null

    // ── 回调缓存 ──
    /** 选区确认回调，选题框用户确认选区后触发 */
    private var onRectSelected: ((Rect) -> Unit)? = null

    /** 答案弹窗关闭回调 */
    private var onAnswerDismissed: (() -> Unit)? = null

    // ── 上下文 ──
    private var appContext: Context? = null

    // ==================== 状态枚举 ====================

    /**
     * 悬浮窗状态枚举。
     * - [IDLE]：所有悬浮窗均已销毁
     * - [SELECTING]：选题框正在显示
     * - [ANSWERING]：答案弹窗正在显示
     */
    enum class FloatWindowState {
        IDLE,
        SELECTING,
        ANSWERING
    }

    /**
     * 当前搜题模式。
     * - [ACCESSIBILITY]：无障碍模式
     * - [SCREEN_CAPTURE]：录屏模式
     */
    enum class SearchMode {
        ACCESSIBILITY,
        SCREEN_CAPTURE
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
     * @param context 上下文（建议传 Activity）
     * @param onRectSelected 选区确认回调，用户拖拽确认选区后返回屏幕坐标 Rect
     */
    fun showSelectOverlay(context: Context, onRectSelected: (Rect) -> Unit) {
        val ctx = context.applicationContext
        // 缓存回调
        this.onRectSelected = onRectSelected

        // 如果已有答案弹窗，先销毁
        if (currentState == FloatWindowState.ANSWERING) {
            destroyAnswerWindow()
        }

        // 如果已有选题框，不重复创建
        if (currentState == FloatWindowState.SELECTING) return

        selectOverlay = FloatSelectOverlay(ctx).apply {
            // 点击 X 关闭按钮 → 销毁
            onDismiss = {
                destroyAll()
            }

            // 用户确认选区 → 回调坐标，销毁选题框
            onRectConfirmed = { rect ->
                this@FloatWindowManager.onRectSelected?.invoke(rect)
                destroySelectOverlay()
            }

            // 附加到窗口
            attachToWindow()
        }

        currentState = FloatWindowState.SELECTING
    }

    // ==================== 显示答案弹窗 ====================

    /**
     * 显示答案弹窗 [AnswerFloatWindow]。
     *
     * 如果当前有选题框，会先销毁再显示答案弹窗。
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

        // 如果已有选题框，先销毁
        if (currentState == FloatWindowState.SELECTING) {
            destroySelectOverlay()
        }

        // 如果已有答案弹窗，先销毁旧的
        if (currentState == FloatWindowState.ANSWERING) {
            destroyAnswerWindow()
        }

        answerWindow = AnswerFloatWindow(ctx).apply {
            // 设置答案内容
            setContent(answer, explanation)

            // 点击返回箭头 → 销毁答案弹窗，重新打开选题框
            // 使用 appContext 而非原始 context，避免 Activity 销毁后持有悬空引用
            onBackPressed = {
                destroyAnswerWindow()
                val cachedCallback = this@FloatWindowManager.onRectSelected
                if (cachedCallback != null && appContext != null) {
                    showSelectOverlay(appContext!!, cachedCallback)
                }
            }

            // 点击关闭 → 销毁一切
            onDismiss = {
                destroyAll()
                this@FloatWindowManager.onAnswerDismissed?.invoke()
            }

            // 附加到窗口
            attachToWindow()
        }

        currentState = FloatWindowState.ANSWERING
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
     * 此方法会先检查录屏服务是否已在运行：
     * - 已在运行 → 直接显示选题框，选区回调路由到录屏服务
     * - 未运行 → 需要通过 [switchToScreenCapture] 先启动录屏服务
     *
     * @param context 上下文
     */
    fun switchToScreenCaptureMode(context: Context) {
        if (ScreenCaptureService.isRunning()) {
            // 录屏服务已在运行 → 直接切换模式
            currentSearchMode = SearchMode.SCREEN_CAPTURE
            showSelectOverlayForScreenCapture(context)
        } else {
            // 录屏服务未运行 → 提示用户先授权
            showAnswerWindow(
                context,
                answer = "切换到录屏模式",
                explanation = "需要先授权录屏权限才能使用录屏搜题。\n\n" +
                        "请通过以下方式启动：\n" +
                        "1. 悬浮球长按选择「录屏模式」\n" +
                        "2. 授权系统录屏权限\n" +
                        "3. 重新选题开始搜题",
                onDismissed = {
                    // 保持无障碍模式
                    currentSearchMode = SearchMode.ACCESSIBILITY
                }
            )
        }
    }

    /**
     * 获取当前搜题模式。
     */
    fun getCurrentSearchMode(): SearchMode = currentSearchMode

    // ==================== 功能面板管理 ====================

    /** 功能面板实例 */
    private var functionPanel: FunctionPanelView? = null

    /**
     * 显示功能面板。
     *
     * @param context 上下文
     * @param panelX 面板左上角 X（屏幕坐标）
     * @param panelY 面板左上角 Y（屏幕坐标）
     * @param panelWidth 面板宽度
     * @param panelHeight 面板高度
     * @param onScreenCaptureClick 录屏搜题回调
     * @param onAccessibilityClick 无障碍搜题回调
     * @param onCameraClick 相机扫描回调
     * @param onCloseClick 关闭悬浮窗回调
     */
    fun showFunctionPanel(
        context: Context,
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        onScreenCaptureClick: () -> Unit,
        onAccessibilityClick: () -> Unit,
        onCameraClick: () -> Unit,
        onCloseClick: () -> Unit
    ) {
        dismissFunctionPanel()

        val ctx = context.applicationContext
        functionPanel = FunctionPanelView(ctx).apply {
            this.onDismiss = {
                dismissFunctionPanel()
            }
            this.onScreenCaptureClick = {
                dismissFunctionPanel()
                onScreenCaptureClick()
            }
            this.onAccessibilityClick = {
                dismissFunctionPanel()
                onAccessibilityClick()
            }
            this.onCameraClick = {
                dismissFunctionPanel()
                onCameraClick()
            }
            this.onCloseClick = {
                dismissFunctionPanel()
                onCloseClick()
            }
            attachToWindow(panelX, panelY, panelWidth, panelHeight)
        }
    }

    /**
     * 关闭功能面板。
     */
    fun dismissFunctionPanel() {
        functionPanel?.detachFromWindow()
        functionPanel = null
    }

    // ==================== 销毁逻辑 ====================

    /**
     * 仅销毁选题框。
     */
    private fun destroySelectOverlay() {
        selectOverlay?.detachFromWindow()
        selectOverlay = null
        if (currentState == FloatWindowState.SELECTING) {
            currentState = FloatWindowState.IDLE
        }
    }

    /**
     * 仅销毁答案弹窗。
     */
    private fun destroyAnswerWindow() {
        answerWindow?.detachFromWindow()
        answerWindow = null
        if (currentState == FloatWindowState.ANSWERING) {
            currentState = FloatWindowState.IDLE
        }
    }

    /**
     * 销毁所有悬浮窗，清除所有回调，回到 IDLE 状态。
     */
    fun destroyAll() {
        destroySelectOverlay()
        destroyAnswerWindow()
        onRectSelected = null
        onAnswerDismissed = null
        currentSearchMode = SearchMode.ACCESSIBILITY
        currentState = FloatWindowState.IDLE
    }

    // ==================== 状态查询 ====================

    /** 获取当前悬浮窗状态 */
    fun getCurrentState(): FloatWindowState = currentState

    /** 选题框是否正在显示 */
    fun isSelecting(): Boolean = currentState == FloatWindowState.SELECTING

    /** 答案弹窗是否正在显示 */
    fun isAnswering(): Boolean = currentState == FloatWindowState.ANSWERING

    /** 获取当前缓存的选区回调（用于无障碍服务获取后续选区） */
    fun getCachedRectCallback(): ((Rect) -> Unit)? = onRectSelected
}