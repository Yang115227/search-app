package com.smartsearch.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.smartsearch.app.core.permission.PermissionManager
import com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService
import com.smartsearch.app.feature.search.floatview.FunctionPanelView

/**
 * 悬浮球前台服务 —— 保活并在屏幕上显示可拖拽悬浮球。
 *
 * # 悬浮球交互
 * - 单击：弹出功能面板（录屏搜题、无障碍搜题、相机扫描、关闭悬浮窗）
 * - 长按：关闭悬浮窗
 * - 拖拽：移动悬浮球位置，松手自动吸附屏幕左右边缘
 *
 * # 防抖机制
 * 在 accessibility 模式下锁定坐标，消除悬浮球抖动 bug。
 *
 * # 前台服务通知
 * Android 8+ 必须创建通知渠道并显示前台通知，否则 Service 会被系统杀死。
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1001

        /** Intent Extra: 启动录屏搜题 */
        const val EXTRA_START_SCREEN_CAPTURE = "start_screen_capture"

        /** 悬浮球默认边长 dp */
        private const val BALL_SIZE_DP = 52f

        /** 长按阈值（毫秒） */
        private const val LONG_PRESS_MS = 800L

        /** 触摸死区（像素），用于防抖 */
        private const val TOUCH_DEAD_ZONE_PX = 5f

        /** 拖动判定的最小距离（像素），低于此距离视为点击 */
        private const val DRAG_THRESHOLD_PX = 20f

        /** 吸附动画总时长（毫秒） */
        private const val SNAP_DURATION_MS = 200L
    }

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager? = null
    private var floatingBallView: FloatingBallView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false

    /** 功能面板实例 */
    private var functionPanel: FunctionPanelView? = null

    // ==================== 触摸状态 ====================

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var touchStartTime = 0L
    private var isDragging = false
    private var isLongPressed = false

    /** 防抖锁定坐标：在 accessibility 模式下锁定，防止悬浮球抖动 */
    private var lastStableX = 0
    private var lastStableY = 0
    private var antiShakeEnabled = false

    // ==================== 尺寸 ====================

    private var ballSizePx = 0
    private var density = 1f
    private var screenWidth = 0
    private var screenHeight = 0

    // ==================== 主线程 Handler ====================

    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        isLongPressed = true
        stopSelf()
        FloatWindowManager.destroyAll()
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        AccessibilitySearchServiceHolder.serviceInstance = this
        density = resources.displayMetrics.density
        ballSizePx = (BALL_SIZE_DP * density).toInt()
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // 检查悬浮窗权限
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            // 权限未授予 → 不显示悬浮球，直接弹窗引导
            notifyPermissionRequired()
        } else {
            attachFloatingBall()
        }

        @Suppress("DEPRECATION")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dismissFunctionPanel()
        detachFloatingBall()
        mainHandler.removeCallbacks(longPressRunnable)
        super.onDestroy()
    }

    // ==================== 前台通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "智能搜题",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球搜题服务运行中"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("智能搜题运行中")
                .setContentText("点击悬浮球开始搜题")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("智能搜题运行中")
                .setContentText("点击悬浮球开始搜题")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ==================== 权限引导 ====================

    /**
     * 悬浮窗权限未授予时，通过 AnswerFloatWindow 弹窗引导用户授权。
     */
    private fun notifyPermissionRequired() {
        FloatWindowManager.showAnswerWindow(
            this,
            answer = "需要悬浮窗权限",
            explanation = "智能搜题需要悬浮窗权限才能在屏幕上显示悬浮球和搜题结果。\n\n" +
                    "请点击下方按钮前往系统设置开启悬浮窗权限。",
            onDismissed = {
                // 跳转悬浮窗权限设置页
                val intent = PermissionManager.getFloatingWindowSettingsIntent(this)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        )
    }

    // ==================== 悬浮球视图 ====================

    /**
     * 自定义 View：Canvas 绘制圆形绿色悬浮球，内容为白色"搜"字。
     */
    private inner class FloatingBallView(context: Context) : View(context) {

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.FILL
        }

        private val circleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - 2f * density

            // 圆形绿色背景
            canvas.drawCircle(cx, cy, radius, circlePaint)
            // 白色描边
            canvas.drawCircle(cx, cy, radius, circleBorderPaint)
            // 白色"搜"字
            val textY = cy - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText("搜", cx, textY, textPaint)
        }
    }

    private fun createFloatingBallView(): View {
        return FloatingBallView(this).apply {
            setOnTouchListener { _, event -> handleTouchEvent(event) }
        }
    }

    /**
     * 将悬浮球添加到 WindowManager。
     */
    private fun attachFloatingBall() {
        if (isViewAttached) return

        floatingBallView = createFloatingBallView() as FloatingBallView

        windowParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            width = ballSizePx
            height = ballSizePx
            gravity = Gravity.TOP or Gravity.START
            // 默认位置：屏幕右侧中偏下
            x = screenWidth - ballSizePx - 16
            y = (screenHeight * 0.6f).toInt()

            format = PixelFormat.TRANSLUCENT
        }

        lastStableX = windowParams!!.x
        lastStableY = windowParams!!.y

        try {
            windowManager?.addView(floatingBallView, windowParams)
            isViewAttached = true
        } catch (e: SecurityException) {
            Log.e(TAG, "悬浮窗权限未授予", e)
            isViewAttached = false
        }
    }

    /**
     * 从 WindowManager 移除悬浮球。
     */
    private fun detachFloatingBall() {
        if (!isViewAttached) return
        try {
            windowManager?.removeView(floatingBallView)
        } catch (e: IllegalArgumentException) {
            // 已移除
        }
        isViewAttached = false
        floatingBallView = null
    }

    /**
     * 更新悬浮球位置，带防抖逻辑。
     */
    private fun updateBallPosition(newX: Int, newY: Int) {
        val params = windowParams ?: return
        params.x = newX
        params.y = newY
        try {
            windowManager?.updateViewLayout(floatingBallView, params)
        } catch (e: IllegalArgumentException) {
            // 视图已被移除
        }
    }

    // ==================== 触摸事件处理 ====================

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWindowX = windowParams?.x ?: 0
                initialWindowY = windowParams?.y ?: 0
                touchStartTime = System.currentTimeMillis()
                isDragging = false
                isLongPressed = false

                // 启动长按检测
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                // 触摸死区：移动距离小于 5px 时忽略，防止微颤
                if (kotlin.math.abs(dx) > TOUCH_DEAD_ZONE_PX || kotlin.math.abs(dy) > TOUCH_DEAD_ZONE_PX) {
                    // 取消长按检测
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                // 拖动判定：移动距离大于 20px 时才真正判定为拖动
                if (kotlin.math.abs(dx) > DRAG_THRESHOLD_PX || kotlin.math.abs(dy) > DRAG_THRESHOLD_PX) {
                    isDragging = true
                }

                if (isDragging && windowParams != null) {
                    val newX = (initialWindowX + dx).toInt()
                    val newY = (initialWindowY + dy).toInt()

                    // 防抖：在 accessibility 模式下，如果坐标变化极小则使用锁定坐标
                    if (antiShakeEnabled) {
                        val shakeDx = kotlin.math.abs(newX - lastStableX)
                        val shakeDy = kotlin.math.abs(newY - lastStableY)
                        if (shakeDx < TOUCH_DEAD_ZONE_PX && shakeDy < TOUCH_DEAD_ZONE_PX) {
                            // 抖动过小，忽略此次更新
                            return true
                        }
                    }

                    lastStableX = newX
                    lastStableY = newY
                    updateBallPosition(newX, newY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val elapsed = System.currentTimeMillis() - touchStartTime
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                when {
                    // 长按触发 → 已通过 Runnable 关闭服务，此处不再重复处理
                    isLongPressed -> {
                        // 已处理
                    }
                    // 点击（短按且未拖拽）→ 弹出功能面板
                    !isDragging && elapsed < LONG_PRESS_MS && distance < DRAG_THRESHOLD_PX * 2 -> {
                        showFunctionPanel()
                    }
                    // 拖拽结束 → 吸附边缘
                    isDragging -> {
                        snapToEdge()
                    }
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                isDragging = false
                return true
            }
        }
        return false
    }

    // ==================== 吸附边缘 ====================

    /**
     * 拖拽结束后将悬浮球吸附到屏幕左边缘或右边缘。
     * 根据悬浮球中心点距离最近边缘决定吸附方向。
     */
    private fun snapToEdge() {
        val params = windowParams ?: return
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val centerX = params.x + ballSizePx / 2

        // 计算目标 X 坐标
        val targetX = if (centerX < screenW / 2) {
            0
        } else {
            screenW - ballSizePx
        }

        // 约束 Y 在屏幕范围内
        val clampedY = params.y.coerceIn(0, screenHeight - ballSizePx)

        params.x = targetX
        params.y = clampedY

        lastStableX = targetX
        lastStableY = clampedY

        try {
            windowManager?.updateViewLayout(floatingBallView, params)
        } catch (e: IllegalArgumentException) {
            // 忽略
        }
    }

    // ==================== 功能面板管理 ====================

    /**
     * 在悬浮球附近弹出功能面板。
     */
    private fun showFunctionPanel() {
        if (functionPanel != null) return

        val params = windowParams ?: return
        val ballCenterX = params.x + ballSizePx / 2
        val ballBottom = params.y + ballSizePx

        // 面板默认显示在悬浮球下方，如果空间不足则显示在上方
        val panelWidth = 280f * density
        val panelHeight = 320f * density
        val panelX = (ballCenterX - panelWidth / 2).toInt().coerceIn(0, (screenWidth - panelWidth).toInt())
        val panelY: Int

        if (ballBottom + panelHeight + 20f * density < screenHeight) {
            panelY = (ballBottom + 10f * density).toInt()
        } else {
            panelY = (params.y - panelHeight - 10f * density).toInt().coerceAtLeast(0)
        }

        val panel = FunctionPanelView(this).apply {
            onDismiss = {
                dismissFunctionPanel()
            }
            onScreenCaptureClick = {
                dismissFunctionPanel()
                startScreenCaptureMode()
            }
            onAccessibilityClick = {
                dismissFunctionPanel()
                triggerAccessibilitySearch()
            }
            onCameraClick = {
                dismissFunctionPanel()
                triggerCameraSearch()
            }
            onCloseClick = {
                dismissFunctionPanel()
                stopSelf()
                FloatWindowManager.destroyAll()
            }
        }

        functionPanel = panel
        panel.attachToWindow(panelX, panelY, panelWidth.toInt(), panelHeight.toInt())
    }

    /**
     * 关闭功能面板。
     */
    fun dismissFunctionPanel() {
        functionPanel?.detachFromWindow()
        functionPanel = null
    }

    // ==================== 搜题触发入口 ====================

    /**
     * 触发无障碍搜题流程（进入连续搜题模式）。
     *
     * 流程：
     * 1. 检查悬浮窗权限
     * 2. 显示选题框（可拖拽/右下角缩放）
     * 3. 用户调整好区域 → 点击「开始搜题」
     * 4. 选区框自动隐藏 → 进入连续搜题模式（3 秒轮询）
     * 5. 底部答案弹窗常驻，实时展示识别结果
     */
    private fun triggerAccessibilitySearch() {
        // 检查悬浮窗权限
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            notifyPermissionRequired()
            return
        }

        // 检查无障碍服务是否运行
        val service = AccessibilitySearchServiceHolder.instance
        if (service == null) {
            Log.w(TAG, "无障碍服务未运行，无法进行搜题")
            FloatWindowManager.destroyAll()
            FloatWindowManager.showAnswerWindow(
                this,
                answer = "无障碍服务未开启",
                explanation = "请在「设置 → 无障碍 → 智能搜题」中开启无障碍服务，\n" +
                        "或点击下方按钮切换到录屏模式。"
            )
            return
        }

        // 显示选题框，注册选区回调 → 进入连续搜题模式
        FloatWindowManager.showSelectOverlay(this) { rect ->
            service.setSelectionRect(rect)
        }
    }

    /**
     * 启动录屏搜题模式。
     *
     * 流程：
     * 1. 检查悬浮窗权限
     * 2. 如已授权，启动 HomeActivity 触发系统录屏授权对话框
     * 3. 用户授权后创建 MediaProjection，弹出选区框
     */
    private fun startScreenCaptureMode() {
        // 检查悬浮窗权限
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            notifyPermissionRequired()
            return
        }

        // 启动 HomeActivity 触发录屏授权流程
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_START_SCREEN_CAPTURE, true)
        }
        if (intent != null) {
            startActivity(intent)
        }
    }

    /**
     * 触发相机扫描搜题。
     */
    private fun triggerCameraSearch() {
        // 相机搜题入口，后续可扩展
        FloatWindowManager.showAnswerWindow(
            this,
            answer = "相机扫描",
            explanation = "相机扫描功能开发中，敬请期待。\n\n" +
                    "当前支持：\n" +
                    "• 无障碍搜题（自动识别屏幕文字）\n" +
                    "• 录屏搜题（通过 OCR 识别）"
        )
    }

    // ==================== 防抖控制 ====================

    /**
     * 启用/禁用防抖模式。
     * 在 accessibility 服务运行时应启用，防止悬浮球抖动。
     */
    fun setAntiShakeEnabled(enabled: Boolean) {
        antiShakeEnabled = enabled
    }

    // ==================== 静态持有者 ====================

    /**
     * 持有 AccessibilitySearchService 实例的静态引用。
     *
     * 在 [AccessibilitySearchService.onServiceConnected] 中设置，
     * 在 [AccessibilitySearchService.onDestroy] 中清除。
     */
    object AccessibilitySearchServiceHolder {
        @Volatile
        var instance: AccessibilitySearchService? = null

        /** 当前 FloatingWindowService 实例 */
        @Volatile
        var serviceInstance: FloatingWindowService? = null
    }
}