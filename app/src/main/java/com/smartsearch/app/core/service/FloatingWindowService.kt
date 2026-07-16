package com.smartsearch.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService
import com.smartsearch.app.core.permission.PermissionManager

/**
 * 悬浮球前台服务 —— 保活并在屏幕上显示可拖拽悬浮球。
 *
 * # 悬浮球交互
 * - 单击：触发搜题流程（选题框 → 无障碍扫描 → 答案弹窗）
 * - 长按：关闭悬浮球
 * - 拖拽：移动悬浮球位置
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

        /** 悬浮球默认边长 dp */
        private const val BALL_SIZE_DP = 52f
    }

    // ==================== 窗口管理 ====================

    private var windowManager: WindowManager? = null
    private var floatingBallView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false

    // ==================== 触摸状态 ====================

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var touchStartTime = 0L
    private var isDragging = false

    // ==================== 尺寸 ====================

    private var ballSizePx = 0
    private var density = 1f

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        density = resources.displayMetrics.density
        ballSizePx = (BALL_SIZE_DP * density).toInt()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ 前台服务必须先调用 startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        attachFloatingBall()
        @Suppress("DEPRECATION")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detachFloatingBall()
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
        // 点击通知可打开主界面
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

    // ==================== 悬浮球视图 ====================

    /**
     * 创建悬浮球 View（圆形绿底白字搜索图标）。
     *
     * 实际项目中应替换为 ImageView + 自定义 drawable 或 Compose 绘制的图标。
     * 此处为简化实现，使用文字图标 "搜"。
     */
    private fun createFloatingBallView(): View {
        return ImageView(this).apply {
            // 使用系统内置搜索图标作为占位
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundColor(0xCC4CAF50.toInt()) // 半透明绿色
            setPadding(8, 8, 8, 8)

            setOnTouchListener { _, event -> handleTouchEvent(event) }
        }
    }

    /**
     * 将悬浮球添加到 WindowManager。
     */
    private fun attachFloatingBall() {
        if (isViewAttached) return

        floatingBallView = createFloatingBallView()

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels

        windowParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            width = ballSizePx
            height = ballSizePx
            gravity = Gravity.TOP or Gravity.START
            // 默认位置：屏幕右侧中偏下
            x = screenWidth - ballSizePx - 16
            y = (metrics.heightPixels * 0.6f).toInt()

            format = PixelFormat.TRANSLUCENT
        }

        try {
            windowManager?.addView(floatingBallView, windowParams)
            isViewAttached = true
        } catch (e: SecurityException) {
            Log.e(TAG, "悬浮窗权限未授予", e)
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
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                // 5px 死区阈值，防止微颤导致频繁位置更新
                if (kotlin.math.abs(dx) > 5f || kotlin.math.abs(dy) > 5f) {
                    isDragging = true
                }

                if (isDragging && windowParams != null) {
                    windowParams?.x = (initialWindowX + dx).toInt()
                    windowParams?.y = (initialWindowY + dy).toInt()
                    try {
                        windowManager?.updateViewLayout(floatingBallView, windowParams)
                    } catch (e: IllegalArgumentException) {
                        // 视图已被移除
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - touchStartTime
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                when {
                    // 长按超过 800ms → 关闭悬浮球
                    elapsed > 800 && distance < 20f -> {
                        stopSelf()
                        FloatWindowManager.destroyAll()
                    }
                    // 点击（短按且未拖拽）→ 触发搜题
                    !isDragging && elapsed < 500 && distance < 20f -> {
                        triggerSelectionSearch()
                    }
                    // 拖拽结束 → 吸附边缘
                    isDragging -> {
                        snapToEdge()
                    }
                }
                // 重置拖拽状态，避免下次 UP 误触发
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    /**
     * 拖拽结束后将悬浮球吸附到屏幕左边缘或右边缘。
     */
    private fun snapToEdge() {
        val params = windowParams ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val centerX = params.x + ballSizePx / 2

        // 吸附到最近边缘
        params.x = if (centerX < screenWidth / 2) {
            0
        } else {
            screenWidth - ballSizePx
        }

        try {
            windowManager?.updateViewLayout(floatingBallView, params)
        } catch (e: IllegalArgumentException) {
            // 忽略
        }
    }

    // ==================== 搜题触发入口 ====================

    /**
     * 悬浮球点击时触发搜题流程。
     *
     * # 完整调用链
     * ```
     * 1. 检查悬浮窗权限 → 未授权则引导用户
     * 2. FloatWindowManager.showSelectOverlay() 显示选题框
     * 3. 用户拖拽确认选区 → 回调 Rect
     * 4. 获取 AccessibilitySearchService 实例 → 传入选区 Rect
     * 5. AccessibilitySearchService.setSelectionRect(rect) 触发扫描
     * 6. 扫描成功 → FloatWindowManager.showAnswerWindow() 显示答案
     * 7. 扫描失败 → 弹出空结果提示，引导切换录屏模式
     * ```
     *
     * 注意：实际项目中需要维护 AccessibilitySearchService 的引用。
     * 可选方案：
     * - 通过 EventBus / LiveData 传递选区 Rect
     * - 通过静态方法/单例持有服务实例
     * - 通过 BroadcastReceiver 跨进程通信
     *
     * 此处使用静态引用方式（简洁，适合单进程场景）。
     */
    private fun triggerSelectionSearch() {
        // 第一步：检查悬浮窗权限
        if (PermissionManager.checkFloatingWindow(this) != PermissionManager.PermissionStatus.GRANTED) {
            // 权限未授予 → 跳转设置页
            val intent = PermissionManager.getFloatingWindowSettingsIntent(this)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        // 第二步：显示选题框，注册选区回调
        FloatWindowManager.showSelectOverlay(this) { rect: Rect ->
            // 第三步：将选区传递给无障碍服务
            // 方式 A：通过单例/静态引用获取服务实例
            val service = AccessibilitySearchServiceHolder.instance
            if (service != null) {
                service.setSelectionRect(rect)
            } else {
                // 无障碍服务未运行 → 提示用户开启
                Log.w(TAG, "无障碍服务未运行，无法进行搜题")
                FloatWindowManager.destroyAll()
                FloatWindowManager.showAnswerWindow(
                    this,
                    answer = "无障碍服务未开启",
                    explanation = "请在「设置 → 无障碍 → 智能搜题」中开启无障碍服务，\n" +
                            "或点击下方按钮切换到录屏模式。"
                )
            }
        }
    }

    /**
     * 持有 AccessibilitySearchService 实例的静态引用。
     *
     * 在 [AccessibilitySearchService.onServiceConnected] 中设置，
     * 在 [AccessibilitySearchService.onDestroy] 中清除。
     */
    object AccessibilitySearchServiceHolder {
        @Volatile
        var instance: AccessibilitySearchService? = null
    }
}