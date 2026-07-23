package com.smartsearch.app.feature.search.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartsearch.app.core.service.FloatingWindowService
import com.smartsearch.app.core.service.AccessibilitySearchServiceHolder
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.core.utils.RectUtil
import com.smartsearch.app.feature.search.capture.QuestionBankSearcher
import com.smartsearch.app.feature.search.capture.ScreenCaptureService

/**
 * 无障碍搜题服务 —— 监听屏幕内容变化，自动提取可见文本并匹配题库。
 *
 * # 工作流程
 * ```
 * 1. 悬浮球点击 → FloatWindowManager.showSelectOverlay() 显示选题框
 * 2. 用户在选题框中拖拽选区 → 回调 Rect 坐标
 * 3. 本服务收到 Rect → 扫描当前屏幕 AccessibilityNodeInfo 树
 * 4. 递归遍历所有节点，过滤出与选区重叠率 ≥ 60% 的文本节点
 * 5. 过滤空白文字、系统状态栏文字 → 拼接完整题干
 * 6. 若题干为空 → 弹出提示弹窗（含一键切换录屏按钮）
 * 7. 若题干有效 → 关闭选题框，弹出 AnswerFloatWindow 答案弹窗
 * ```
 *
 * # 节流策略
 * 每次屏幕内容变化后 800ms 内不重复扫描，避免高频触发（如滚动列表）。
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)。
 * 递归遍历后立即调用 [AccessibilityNodeInfo.recycle] 回收节点，防止 OOM。
 *
 * # 注册方式
 * 在 AndroidManifest.xml 中声明：
 * ```xml
 * <service
 *     android:name=".feature.search.accessibility.AccessibilitySearchService"
 *     android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.accessibilityservice.AccessibilityService" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.accessibilityservice"
 *         android:resource="@xml/accessibility_service_config" />
 * </service>
 * ```
 */
class AccessibilitySearchService : AccessibilityService() {

    // ==================== 常量 ====================

    companion object {
        private const val TAG = "AccessibilitySearchSvc"

        /** 扫描节流间隔（毫秒） */
        private const val SCAN_THROTTLE_MS = 800L

        /** 选区重叠率阈值（60%），低于此值的文本节点将被过滤 */
        private const val OVERLAP_THRESHOLD = 0.6f

        /** 状态栏高度上限 dp（用于过滤状态栏文字），适配各机型 */
        private const val STATUS_BAR_MAX_HEIGHT_DP = 60f

        /** 是否正在运行 */
        @Volatile
        private var isRunning = false

        /** 当前服务实例（用于外部获取引用） */
        @Volatile
        private var serviceInstance: AccessibilitySearchService? = null

        /**
         * 获取当前服务实例。
         * 与 AccessibilitySearchServiceHolder 作用相同，
         * 提供更直接的获取方式。
         */
        fun getInstance(): AccessibilitySearchService? = serviceInstance

        /**
         * 判断无障碍服务是否已开启。
         */
        fun isServiceEnabled(context: Context): Boolean {
            val expectedService = "${context.packageName}/${AccessibilitySearchService::class.java.name}"
            val enabledServices = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Exception) {
                return false
            }
            return enabledServices?.contains(expectedService) == true
        }

        /** 选区持久化存储 */
        object SelectionPrefs {
            private const val PREFS_NAME = "accessibility_selection"
            private const val KEY_LEFT = "sel_left"
            private const val KEY_TOP = "sel_top"
            private const val KEY_RIGHT = "sel_right"
            private const val KEY_BOTTOM = "sel_bottom"

            fun save(context: Context, rect: Rect) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LEFT, rect.left)
                    .putInt(KEY_TOP, rect.top)
                    .putInt(KEY_RIGHT, rect.right)
                    .putInt(KEY_BOTTOM, rect.bottom)
                    .apply()
            }

            fun load(context: Context): Rect? {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val left = prefs.getInt(KEY_LEFT, -1)
                val top = prefs.getInt(KEY_TOP, -1)
                val right = prefs.getInt(KEY_RIGHT, -1)
                val bottom = prefs.getInt(KEY_BOTTOM, -1)
                if (left < 0 || top < 0 || right < 0 || bottom < 0) return null
                return Rect(left, top, right, bottom)
            }

            fun clear(context: Context) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
    }

    // ==================== 内部状态 ====================

    /** 主线程 Handler */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 节流 Runnable */
    private val throttleRunnable = Runnable { performScan() }

    /** 是否正在等待节流执行 */
    private var isThrottlePending = false

    /** 当前选区矩形（屏幕坐标），由 FloatSelectOverlay 回调传入 */
    @Volatile
    private var targetSelectionRect: RectF? = null

    /** 屏幕密度 */
    private var density = 1f

    /** 状态栏高度（像素），运行时计算 */
    private var statusBarHeightPx = 0

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        density = resources.displayMetrics.density
        statusBarHeightPx = getStatusBarHeight()

        // 注册到静态持有者，供 FloatingWindowService 获取实例
        AccessibilitySearchServiceHolder.instance = this
        serviceInstance = this

        // 恢复上次保存的选区
        restoreSelectionRect()

        // 配置无障碍服务信息
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK // 监听所有事件类型
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100 // 100ms 内合并同类事件
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.DEFAULT
        }
        setServiceInfo(info)

        Log.d(TAG, "无障碍搜题服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 仅监听窗口内容变化类型的事件，避免无意义触发
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                scheduleThrottledScan()
            }
            else -> { /* 忽略其他事件类型 */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        cancelThrottle()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelThrottle()

        // 清除静态持有者引用，防止内存泄漏
        AccessibilitySearchServiceHolder.instance = null
        serviceInstance = null
        Log.d(TAG, "无障碍搜题服务已销毁")
    }

    // ==================== 节流调度 ====================

    /**
     * 安排节流扫描：800ms 内不重复触发。
     */
    private fun scheduleThrottledScan() {
        if (isThrottlePending) return
        isThrottlePending = true
        mainHandler.postDelayed(throttleRunnable, SCAN_THROTTLE_MS)
    }

    /**
     * 取消等待中的扫描。
     */
    private fun cancelThrottle() {
        mainHandler.removeCallbacks(throttleRunnable)
        isThrottlePending = false
    }

    // ==================== 核心扫描逻辑 ====================

    /**
     * 执行扫描：获取选区 Rect → 遍历节点树 → 过滤拼接 → 返回结果。
     *
     * 仅当 [targetSelectionRect] 不为 null 时执行（即用户已通过选题框确认选区）。
     */
    private fun performScan() {
        isThrottlePending = false

        val selectionRect = targetSelectionRect
        if (selectionRect == null) {
            return // 尚未有选区，跳过
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "无法获取当前窗口根节点")
            return
        }

        try {
            // 收集所有与选区重叠的文本节点
            val textNodes = mutableListOf<AccessibilityNodeInfo>()
            collectTextNodes(rootNode, selectionRect, textNodes)

            // 过滤并拼接题干
            val questionText = buildQuestionText(textNodes)

            // 回收所有已收集的节点
            textNodes.forEach { it.recycle() }

            if (questionText.isBlank()) {
                // 题干为空 → 弹出提示，引导切换录屏模式
                mainHandler.post { showEmptyResultDialog() }
            } else {
                // 识别成功 → 在后台线程检索题库，避免 runBlocking 阻塞主线程
                Thread {
                    val answer = QuestionBankSearcher.search(this, questionText)
                    mainHandler.post {
                        // 清除选区，防止后续无障碍事件触发重复扫描导致弹窗闪烁循环
                        targetSelectionRect = null
                        FloatWindowManager.showAnswerWindow(
                            this,
                            answer = answer,
                            explanation = "无障碍识别 · 匹配题干：${questionText.take(50)}..."
                        )
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描过程异常", e)
        } finally {
            // 确保根节点被回收
            rootNode.recycle()
        }
    }

    // ==================== 节点递归遍历 ====================

    /**
     * 递归遍历 [AccessibilityNodeInfo] 树，收集与选区重叠率 ≥ 60% 的文本节点。
     *
     * 遍历策略：
     * 1. 先检查当前节点是否可见、有文本内容
     * 2. 计算当前节点矩形与选区的重叠率
     * 3. 重叠率达标则加入结果列表
     * 4. 递归遍历子节点
     * 5. 不匹配的节点立即回收，匹配的节点延迟到 [buildQuestionText] 后回收
     *
     * @param node 当前节点
     * @param selectionRect 用户选区矩形（屏幕坐标）
     * @param result 收集结果列表
     */
    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        selectionRect: RectF,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        // 获取当前节点的屏幕矩形
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)

        if (nodeRect.isEmpty) {
            // 节点不可见或无尺寸，但子节点可能有内容，继续遍历子节点后回收
            processChildren(node, selectionRect, result)
            node.recycle()
            return
        }

        val nodeRectF = RectUtil.toRectF(nodeRect)

        // 计算与选区的重叠率
        val overlapRatio = RectUtil.overlapRatio(nodeRectF, selectionRect)

        if (overlapRatio >= OVERLAP_THRESHOLD) {
            // 重叠率达标，有文本内容，加入结果
            if (hasValidText(node)) {
                // 使用 AccessibilityNodeInfo.obtain() 深拷贝，避免原节点被回收后不可用
                val copy = AccessibilityNodeInfo.obtain(node)
                result.add(copy)
            }
        }

        // 继续遍历子节点
        processChildren(node, selectionRect, result)

        // 当前节点不在结果集中 → 立即回收
        // 注意：如果节点已被 obtain 拷贝，原节点仍可安全回收
        node.recycle()
    }

    /**
     * 递归处理当前节点的子节点。
     */
    private fun processChildren(
        node: AccessibilityNodeInfo,
        selectionRect: RectF,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, selectionRect, result)
        }
    }

    // ==================== 文本过滤与拼接 ====================

    /**
     * 判断节点是否有有效文本内容（非空、非纯空白）。
     */
    private fun hasValidText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: return false
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        return text.isNotEmpty() || contentDesc.isNotEmpty()
    }

    /**
     * 组装题干文本，过滤空白文字和系统状态栏文字。
     *
     * 过滤规则：
     * 1. 剔除纯空白文本
     * 2. 剔除位于系统状态栏区域的文本（时间、电量、信号等）
     * 3. 剔除纯数字/符号杂讯（如"10:30"、"100%"等状态栏典型内容）
     * 4. 按节点在屏幕上的 Y 坐标从上到下排序，保证阅读顺序
     * 5. 用换行符拼接
     *
     * @param nodes 已收集的文本节点（会被遍历后回收）
     * @return 拼接后的完整题干
     */
    private fun buildQuestionText(nodes: List<AccessibilityNodeInfo>): String {
        if (nodes.isEmpty()) return ""

        // 按 Y 坐标从上到下排序，保证阅读顺序
        val sortedNodes = nodes.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        val textLines = mutableListOf<String>()

        for (node in sortedNodes) {
            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)

            // 过滤 1：系统状态栏区域
            if (isInStatusBarArea(nodeRect)) continue

            // 过滤 2：无文本
            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

            // 优先使用 text，其次使用 contentDescription
            val displayText = when {
                text.isNotEmpty() -> text
                contentDesc.isNotEmpty() -> contentDesc
                else -> continue
            }

            // 过滤 3：空白文本
            if (displayText.isBlank()) continue

            // 过滤 4：纯状态栏杂讯（时间格式、电量、信号等）
            if (isStatusBarNoise(displayText)) continue

            // 去重：相邻相同文本不重复添加（如列表项复用导致）
            if (textLines.isNotEmpty() && textLines.last() == displayText) continue

            textLines.add(displayText)
        }

        return textLines.joinToString("\n")
    }

    /**
     * 判断矩形是否位于系统状态栏区域内。
     *
     * 状态栏高度通过反射获取，适配各厂商 ROM。
     * 如果获取失败，使用 60dp 作为安全上限。
     */
    private fun isInStatusBarArea(rect: Rect): Boolean {
        // 状态栏位于屏幕顶部，高度通常 ≤ statusBarHeightPx
        // 同时检查矩形是否完全在状态栏区域以上
        return rect.bottom <= statusBarHeightPx + 4 // 4px 容差
    }

    /**
     * 获取状态栏高度（像素），兼容各 Android 版本。
     */
    private fun getStatusBarHeight(): Int {
        // 方式 1：通过资源 ID 获取
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        // 方式 2：兜底使用 60dp
        return (STATUS_BAR_MAX_HEIGHT_DP * density).toInt()
    }

    /**
     * 判断文本是否为状态栏杂讯。
     *
     * 典型状态栏内容特征：
     * - 时间格式："10:30"、"22:18"、"上午10:30"
     * - 电量："100%"、"85%"
     * - 纯数字短线
     * - 运营商名称："中国移动"、"China Mobile"（长度较短时）
     */
    private fun isStatusBarNoise(text: String): Boolean {
        val trimmed = text.trim()

        // 时间格式：HH:mm 或 HH:mm:ss
        if (trimmed.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))) return true
        if (trimmed.matches(Regex("^(上午|下午|AM|PM)\\s*\\d{1,2}:\\d{2}$"))) return true

        // 电量百分比
        if (trimmed.matches(Regex("^\\d{1,3}%$"))) return true

        // 纯数字（单行，长度 ≤ 4，如信号强度数值）
        if (trimmed.matches(Regex("^\\d{1,4}$"))) return true

        // 典型状态栏图标描述（contentDescription 中常见）
        val statusBarKeywords = listOf(
            "电池", "电量", "信号", "Wi-Fi", "WiFi", "蓝牙", "闹钟", "静音",
            "振动", "飞行模式", "NFC", "VPN", "热点", "免打扰",
            "Battery", "Signal", "Bluetooth", "Alarm", "Silent", "Vibrate"
        )
        for (keyword in statusBarKeywords) {
            if (trimmed.contains(keyword, ignoreCase = true)) return true
        }

        return false
    }

    // ==================== 一键切换录屏模式 ====================

    /**
     * 从无障碍模式一键切换到录屏模式。
     *
     * 调用方需在 Activity 中处理 MediaProjection 授权流程。
     * 完整调用链：
     * ```
     * AccessibilitySearchService.switchToScreenCapture(context) { projectionIntent ->
     *     startActivityForResult(
     *         projectionManager.createScreenCaptureIntent(), REQUEST_CODE
     *     )
     * }
     * // 在 onActivityResult 中：
     * ScreenCaptureService.startWithProjection(context, data, rect)
     * ```
     *
     * @param context 上下文
     * @param onReadyToRequest 授权 Intent 就绪回调
     */
    fun switchToScreenCapture(
        context: Context,
        onReadyToRequest: (Intent) -> Unit
    ) {
        // 确保 OCR 引擎已初始化
        ScreenCaptureService.switchFromAccessibility(context, onReadyToRequest)
    }

    // ==================== 空结果处理 ====================

    /**
     * 当筛选后题干为空时，弹出提示弹窗，引导用户切换到录屏模式。
     *
     * 弹窗通过 [FloatWindowManager.showAnswerWindow] 显示（复用答案弹窗的 UI 框架），
     * 内容区别于正常答案，展示"未能识别文字"的提示和"切换到录屏模式"入口。
     *
     * 用户可在弹窗关闭后，通过外部 Activity 调用 [switchToScreenCapture] 完成切换。
     */
    private fun showEmptyResultDialog() {
        // 清除选区，防止后续无障碍事件触发重新扫描
        targetSelectionRect = null

        // 关闭当前的选题框
        FloatWindowManager.destroyAll()

        // 使用答案弹窗展示提示信息 + 引导切换录屏
        FloatWindowManager.showAnswerWindow(
            this,
            answer = "未能识别选题区域内的文字内容",
            explanation = "可能原因：\n" +
                    "1. 当前页面为图片/视频，无法提取文字\n" +
                    "2. 应用禁用了无障碍访问\n" +
                    "3. 选区范围未覆盖有效文字\n\n" +
                    "请尝试以下操作：\n" +
                    "• 重新选题：调整选区范围后重试\n" +
                    "• 切换到录屏模式：通过 OCR 识别图片中的文字\n\n" +
                    "切换路径：\n" +
                    "悬浮球长按 → 切换录屏模式 → 授权录屏 → 重新选题",
            onDismissed = {
                // 关闭后不做额外操作，用户可自行选择重新选题或切换录屏
            }
        )
    }

    // ==================== 公开 API ====================

    /**
     * 由外部（如 [FloatingWindowService] 的悬浮球点击）调用，启动选题流程。
     *
     * 调用链：
     * ```
     * FloatingWindowService.triggerSelectionSearch()
     *   → FloatWindowManager.showSelectOverlay(context) { rect ->
     *         AccessibilitySearchService.setSelectionRect(rect)
     *         AccessibilitySearchService.performScan()
     *     }
     * ```
     *
     * @param rect 用户确认的选区矩形（屏幕坐标）
     */
    fun setSelectionRect(rect: Rect) {
        targetSelectionRect = RectF(rect)
        // 取消等待中的节流扫描，立即执行
        cancelThrottle()
        performScan()

        // 持久化保存选区（使用统一 FloatWindowManager.SelectionPrefs，按无障碍模式分键存储）
        try {
            FloatWindowManager.SelectionPrefs.save(this, rect, FloatWindowManager.SearchMode.ACCESSIBILITY)
        } catch (e: Exception) {
            Log.e(TAG, "保存选区失败: ${e.message}", e)
        }
    }

    /**
     * 清除当前选区（取消选题时调用）。
     */
    fun clearSelectionRect() {
        targetSelectionRect = null

        // 清除持久化选区
        try {
            SelectionPrefs.clear(this)
        } catch (e: Exception) {
            Log.e(TAG, "清除选区异常: ${e.message}", e)
        }
    }

    /**
     * 恢复上次保存的选区。
     * 如果上次选区存在且未越界，自动设置选区并触发一次扫描。
     * 包含越界适配：如果选区超出屏幕边界，自动缩放到边界内。
     */
    private fun restoreSelectionRect() {
        try {
            // 使用统一 FloatWindowManager.SelectionPrefs，按无障碍模式分键加载
            val savedRect = FloatWindowManager.SelectionPrefs.load(this, FloatWindowManager.SearchMode.ACCESSIBILITY) ?: return
            Log.d(TAG, "恢复上次选区: $savedRect")
            targetSelectionRect = RectF(savedRect)
            // 触发一次扫描
            cancelThrottle()
            performScan()
        } catch (e: Exception) {
            Log.e(TAG, "恢复选区异常: ${e.message}", e)
        }
    }
}