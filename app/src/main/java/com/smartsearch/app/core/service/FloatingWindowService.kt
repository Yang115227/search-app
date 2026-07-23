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
import android.graphics.Typeface
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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 悬浮球前台服务 —— 在屏幕上显示当前搜题模式对应的单个悬浮球。
 *
 * # 悬浮球列表
 * | 模式         | 显示文字 | 背景色       | 点击行为                  |
 * |-------------|---------|-------------|--------------------------|
 * | 无障碍搜题   | 无      | 绿色 #4CAF50 | 弹出选区框 → 进入连续搜题 |
 * | 录屏搜题     | 录      | 蓝色 #2196F3 | 启动录屏授权 → 进入连续搜题 |
 * | 扫描搜题     | 扫      | 橙色 #FF9800 | 打开相机扫描               |
 *
 * # 交互行为
 * - 同一时间只显示一个悬浮球，对应当前启用的搜题模式
 * - 切换模式时自动更换悬浮球
 * - 单击：直接触发对应搜题模式
 * - 长按：关闭悬浮窗（停止服务）
 * - 拖拽：移动悬浮球位置，松手自动吸附左右边缘
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var serviceInstance: FloatingWindowService? = null

        /** 当前搜题模式，默认无障碍 */
        @Volatile
        private var currentMode: FloatWindowManager.SearchMode = FloatWindowManager.SearchMode.ACCESSIBILITY

        /**
         * 切换搜题模式并更新悬浮球。
         * 从页面主按钮调用（无需服务实例引用）。
         */
        fun switchMode(mode: FloatWindowManager.SearchMode) {
            currentMode = mode
            serviceInstance?.mainHandler?.post {
                serviceInstance?.attachBallForMode(mode)
            }
        }

        /**
         * 重新显示当前模式的悬浮球（从搜索模式退出后调用）。
         */
        fun showBall() {
            serviceInstance?.mainHandler?.post {
                serviceInstance?.attachBallForMode(currentMode)
            }
        }

        /** Intent Extra: 启动录屏搜题 */
        const val EXTRA_START_SCREEN_CAPTURE = "start_screen_capture"

        /** Intent Extra: 启动相机扫描搜题 */
        const val EXTRA_START_CAMERA_SEARCH = "start_camera_search"

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

    // ==================== 悬浮球配置 ====================

    /** 单个悬浮球的配置信息 */
    private data class FloatBallConfig(
        val label: String,          // 球内文字
        val color: Int,             // 圆形背景色
        val defaultYRatio: Float,   // 默认 Y 位置比例（0~1）
        val onClick: () -> Unit     // 点击回调
    )

    /** 根据搜题模式获取悬浮球配置，CAMERA 模式不显示悬浮球 */
    private fun getConfigForMode(mode: FloatWindowManager.SearchMode): FloatBallConfig? {
        return when (mode) {
            FloatWindowManager.SearchMode.ACCESSIBILITY -> FloatBallConfig(
                label = "无",
                color = Color.parseColor("#4CAF50"),
                defaultYRatio = 0.35f,
                onClick = { triggerAccessibilitySearch() }
            )
            FloatWindowManager.SearchMode.SCREEN_CAPTURE -> FloatBallConfig(
                label = "录",
                color = Color.parseColor("#2196F3"),
                defaultYRatio = 0.35f,
                onClick = { startScreenCaptureMode() }
            )
            FloatWindowManager.SearchMode.CAMERA -> null // 扫描搜题不启用悬浮球
        }
    }

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager? = null
    private var currentBall: FloatingBallView? = null

    private var density = 1f
    private var ballSizePx = 0
    private var screenWidth = 0
    private var screenHeight = 0

    // ==================== 主线程 Handler ====================

    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
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
            notifyPermissionRequired()
        } else {
            attachBallForMode(currentMode)
        }

        @Suppress("DEPRECATION")
        return START_STICKY
    }

    override fun onDestroy() {
        detachCurrentBall()
        serviceInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                .setContentText("悬浮球搜题服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("智能搜题运行中")
                .setContentText("悬浮球搜题服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ==================== 权限引导 ====================

    private fun notifyPermissionRequired() {
        FloatWindowManager.showAnswerWindow(
            this,
            answer = "需要悬浮窗权限",
            explanation = "智能搜题需要悬浮窗权限才能在屏幕上显示悬浮球和搜题结果。\n\n" +
                    "请点击下方按钮前往系统设置开启悬浮窗权限。",
            onDismissed = {
                val intent = PermissionManager.getFloatingWindowSettingsIntent(this)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        )
    }

    // ==================== 悬浮球管理 ====================

    /**
     * 为指定模式创建并显示悬浮球。
     * 先移除当前球，再创建新模式对应的球。
     * CAMERA 模式不显示悬浮球。
     */
    private fun attachBallForMode(mode: FloatWindowManager.SearchMode) {
        // 先移除当前球
        detachCurrentBall()

        val config = getConfigForMode(mode) ?: return // CAMERA 模式不显示
        val ball = FloatingBallView(this, config)
        val params = createBallParams(config.defaultYRatio)
        ball.ballParams = params
        try {
            windowManager?.addView(ball, params)
            currentBall = ball
        } catch (e: SecurityException) {
            Log.e(TAG, "悬浮窗权限未授予", e)
        }
    }

    /**
     * 创建单个悬浮球的 WindowManager LayoutParams。
     * 默认位置：屏幕右侧，Y 坐标居中偏上。
     */
    private fun createBallParams(yRatio: Float): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
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
            x = screenWidth - ballSizePx - 16
            y = (screenHeight * yRatio).toInt()

            format = PixelFormat.TRANSLUCENT
        }
    }

    /**
     * 从 WindowManager 移除当前悬浮球。
     */
    private fun detachCurrentBall() {
        currentBall?.let { ball ->
            try {
                windowManager?.removeView(ball)
            } catch (e: IllegalArgumentException) {
                // 已移除
            }
        }
        currentBall = null
    }

    /**
     * 隐藏当前悬浮球（进入搜索模式前调用）。
     */
    private fun hideCurrentBall() {
        detachCurrentBall()
    }

    // ==================== 悬浮球视图 ====================

    /**
     * 单个悬浮球 View —— 圆形绿色/蓝色/橙色背景 + 白色文字。
     * 每个球独立管理自己的触摸状态和窗口参数。
     */
    private inner class FloatingBallView(
        context: Context,
        private val config: FloatBallConfig
    ) : View(context) {

        /** 此球的 WindowManager LayoutParams，由外部赋值 */
        var ballParams: WindowManager.LayoutParams? = null

        /** 长按 Runnable（每个球独立，避免引用冲突） */
        private val longPressRunnable = Runnable {
            isLongPressed = true
            stopSelf()
            FloatWindowManager.destroyAll()
        }

        // ── 画笔 ──
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.color
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
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        // ── 触摸状态 ──
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var windowStartX = 0
        private var windowStartY = 0
        private var touchStartTime = 0L
        private var isDragging = false
        private var isLongPressed = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - 2f * density

            // 圆形背景
            canvas.drawCircle(cx, cy, radius, circlePaint)
            // 白色描边
            canvas.drawCircle(cx, cy, radius, circleBorderPaint)
            // 白色文字
            val textY = cy - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(config.label, cx, textY, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    windowStartX = ballParams?.x ?: 0
                    windowStartY = ballParams?.y ?: 0
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false
                    isLongPressed = false

                    // 启动长按检测
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY

                    // 触摸死区：移动距离小于 5px 时忽略，防止微颤
                    if (abs(dx) > TOUCH_DEAD_ZONE_PX || abs(dy) > TOUCH_DEAD_ZONE_PX) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    // 拖动判定：移动距离大于 20px 时才真正判定为拖动
                    if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) {
                        isDragging = true
                    }

                    if (isDragging && ballParams != null) {
                        ballParams!!.x = (windowStartX + dx).toInt()
                        ballParams!!.y = (windowStartY + dy).toInt()
                        updateBallPosition(ballParams!!)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val distance = sqrt(dx * dx + dy * dy)

                    when {
                        isLongPressed -> { /* 已由 Runnable 处理 */ }
                        // 点击 → 触发对应搜题模式
                        !isDragging && elapsed < LONG_PRESS_MS && distance < DRAG_THRESHOLD_PX * 2 -> {
                            config.onClick()
                        }
                        // 拖拽结束 → 吸附边缘
                        isDragging -> {
                            snapToEdge(ballParams)
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
    }

    /**
     * 更新悬浮球位置。
     */
    private fun updateBallPosition(params: WindowManager.LayoutParams) {
        currentBall?.let { ball ->
            try {
                windowManager?.updateViewLayout(ball, params)
            } catch (e: IllegalArgumentException) {
                // 视图已被移除
            }
        }
    }

    /**
     * 拖拽结束后将悬浮球吸附到屏幕左边缘或右边缘。
     */
    private fun snapToEdge(params: WindowManager.LayoutParams?) {
        val p = params ?: return
        val centerX = p.x + ballSizePx / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - ballSizePx
        val clampedY = p.y.coerceIn(0, screenHeight - ballSizePx)

        p.x = targetX
        p.y = clampedY

        currentBall?.let { ball ->
            try {
                windowManager?.updateViewLayout(ball, p)
            } catch (e: IllegalArgumentException) {
                // 忽略
            }
        }
    }

    // ==================== 搜题触发入口 ====================

    private fun triggerAccessibilitySearch() {
        hideCurrentBall()
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            notifyPermissionRequired()
            return
        }

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

        FloatWindowManager.showSelectOverlay(this) { rect ->
            service.setSelectionRect(rect)
        }
    }

    private fun startScreenCaptureMode() {
        hideCurrentBall()
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            notifyPermissionRequired()
            return
        }

        // 启动 Activity 触发录屏授权流程
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_START_SCREEN_CAPTURE, true)
            }
            if (intent != null) {
                startActivity(intent)
            } else {
                Log.w(TAG, "无法获取启动 Intent")
                showScreenCaptureError("无法启动应用，请手动打开应用后重试")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录屏搜题失败: ${e.message}", e)
            showScreenCaptureError("启动录屏搜题失败，请手动打开应用后重试")
        }
    }

    /**
     * 录屏模式启动失败时显示提示。
     */
    private fun showScreenCaptureError(message: String) {
        FloatWindowManager.showAnswerWindow(
            this,
            answer = "录屏启动失败",
            explanation = message + "\n\n" +
                    "请尝试：\n" +
                    "1. 手动打开应用\n" +
                    "2. 点击页面「录屏搜题」按钮\n" +
                    "3. 授权录屏权限",
            onDismissed = {
                // 退出后重新显示悬浮球
                FloatWindowManager.destroyAll()
            }
        )
    }

    private fun triggerCameraSearch() {
        hideCurrentBall()
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            notifyPermissionRequired()
            return
        }

        // 启动相机扫描 Activity
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_START_CAMERA_SEARCH, true)
            }
            if (intent != null) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动相机扫描失败: ${e.message}", e)
            FloatWindowManager.showAnswerWindow(
                this,
                answer = "相机扫描启动失败",
                explanation = "无法启动相机扫描，请手动打开应用后点击「扫描搜题」按钮。"
            )
        }
    }
}

/**
 * AccessibilitySearchService 的弱引用持有者。
 * 在 AccessibilitySearchService.onCreate() 中设置。
 */
object AccessibilitySearchServiceHolder {
    var instance: AccessibilitySearchService? = null
        set(value) {
            field = value
            Log.d("AccessibilitySearchHolder", "AccessibilitySearchService 实例已${if (value != null) "绑定" else "解绑"}")
        }

    /** 服务实例，用于调用 setAntiShakeEnabled 等内部方法 */
    var serviceInstance: AccessibilitySearchService? = null
        set(value) {
            field = value
            instance = value
        }
}