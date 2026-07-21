package com.smartsearch.app.feature.search.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.smartsearch.app.core.permission.PermissionManager
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService
import java.nio.ByteBuffer

/**
 * 录屏搜题服务 —— 通过 MediaProjection 采集屏幕画面，裁剪选区后 OCR 识别。
 *
 * # Android 14 适配要点
 * - 必须在 AndroidManifest.xml 中声明 `foregroundServiceType="mediaProjection"`
 * - 必须在 `AndroidManifest.xml` 的 `<service>` 标签中声明：
 *   ```xml
 *   <service
 *       android:name=".feature.search.capture.ScreenCaptureService"
 *       android:foregroundServiceType="mediaProjection"
 *       android:exported="false" />
 *   ```
 * - 启动时必须传入有效的 MediaProjection Intent（通过 Activity#startActivityForResult 获取）
 * - 系统会在通知栏显示"屏幕录制"指示器（Android 14+ 强制）
 *
 * # 工作流程
 * ```
 * 1. 外部调用 ScreenCaptureService.startWithProjection(context, intent, rect)
 * 2. onCreate → 创建前台通知 → 创建 ImageReader → 创建 VirtualDisplay
 * 3. ImageReader.onImageAvailable → 裁剪选区 → PaddleOCR.recognize()
 * 4. 识别成功 → FloatWindowManager.showAnswerWindow()
 * 5. 识别空白 → 保存 debug 截图 → 弹窗提示
 * 6. 录屏权限回收 / FLAG_SECURE 页面 → 自动切回无障碍模式
 * ```
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 2001

        /** 录屏帧采集间隔（毫秒），200ms = 每秒 5 帧 */
        private const val CAPTURE_INTERVAL_MS = 200L

        /**
         * 连续黑帧阈值：连续 N 帧检测到纯黑画面时，判定为 FLAG_SECURE 页面，
         * 自动切回无障碍模式。
         */
        private const val BLACK_FRAME_THRESHOLD = 3

        /**
         * 黑帧判定亮度阈值：图片平均亮度低于此值视为黑帧。
         */
        private const val BLACK_FRAME_LUMINANCE = 10.0

        // ── Intent Action 常量 ──

        /** 启动录屏采集 */
        const val ACTION_START_CAPTURE = "com.smartsearch.app.action.START_CAPTURE"

        /** 单次截图 */
        const val ACTION_CAPTURE_ONCE = "com.smartsearch.app.action.CAPTURE_ONCE"

        /** 停止录屏 */
        const val ACTION_STOP = "com.smartsearch.app.action.STOP_CAPTURE"

        /**
         * 预启动前台服务（通知栏可见），不创建 MediaProjection。
         * 用于在弹出系统录屏授权对话框前先启动前台服务。
         */
        const val ACTION_PREPARE_CAPTURE = "com.smartsearch.app.action.PREPARE_CAPTURE"

        /**
         * 设置 MediaProjection 授权 Intent（用户授权后调用）。
         * 将授权 Intent 发送给已启动的服务，服务据此创建 MediaProjection 并开始采集。
         */
        const val ACTION_SET_PROJECTION = "com.smartsearch.app.action.SET_PROJECTION"

        /** Intent Extra: MediaProjection 授权 Intent */
        const val EXTRA_PROJECTION_INTENT = "projection_intent"

        /** Intent Extra: 选区矩形 */
        const val EXTRA_SELECTION_RECT = "selection_rect"

        // ── 静态持有者 ──

        @Volatile
        private var instance: ScreenCaptureService? = null

        /**
         * 获取当前服务实例（用于外部触发截图）。
         */
        fun getInstance(): ScreenCaptureService? = instance

        /**
         * 判断录屏服务是否正在运行。
         */
        fun isRunning(): Boolean = instance != null

        /**
         * 以录屏模式启动搜题。
         *
         * @param context 上下文
         * @param projectionIntent MediaProjection 授权 Intent
         * @param selectionRect 用户选区矩形（屏幕坐标）
         */
        fun startWithProjection(
            context: Context,
            projectionIntent: Intent,
            selectionRect: Rect?
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_PROJECTION_INTENT, projectionIntent)
                if (selectionRect != null) {
                    putExtra(EXTRA_SELECTION_RECT, selectionRect)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 预启动前台服务（通知栏可见），不创建 MediaProjection。
         * 在弹出系统录屏授权对话框前调用，确保系统通知栏显示"录屏搜题运行中"。
         *
         * @param context 上下文
         */
        fun startForegroundOnly(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_PREPARE_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 将用户授权后的 MediaProjection Intent 发送给已启动的录屏服务。
         * 服务收到后创建 MediaProjection 并开始采集屏幕画面。
         *
         * @param context 上下文
         * @param projectionIntent 用户授权后返回的 MediaProjection Intent
         * @param selectionRect 选区矩形（可为 null，后续通过 updateSelectionRect 设置）
         */
        fun setProjection(context: Context, projectionIntent: Intent, selectionRect: Rect?) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_SET_PROJECTION
                putExtra(EXTRA_PROJECTION_INTENT, projectionIntent)
                if (selectionRect != null) {
                    putExtra(EXTRA_SELECTION_RECT, selectionRect)
                }
            }
            // 服务已启动，使用 startService 发送 Intent
            context.startService(intent)
        }

        /**
         * 从无障碍模式一键切换到录屏模式。
         *
         * @param context 上下文
         * @param onReadyToRequest 准备就绪回调，调用方需在此启动系统授权对话框
         */
        fun switchFromAccessibility(
            context: Context,
            onReadyToRequest: (Intent) -> Unit
        ) {
            // 先确保 OCR 引擎已初始化
            if (!PaddleOCREngine.isReady()) {
                PaddleOCREngine.init(context)
            }

            // 准备 MediaProjection 授权请求
            val projectionManager = context.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            onReadyToRequest(projectionManager.createScreenCaptureIntent())
        }
    }

    // ==================== 外部启动参数 ====================

    /** 用户选区矩形（屏幕坐标），由 FloatSelectOverlay 回调传入 */
    @Volatile
    private var selectionRect: Rect? = null

    // ==================== MediaProjection 组件 ====================

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** 屏幕尺寸 */
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /** 后台线程（处理 ImageReader 回调） */
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /** 主线程 Handler */
    private val mainHandler = Handler(mainLooper)

    /** 是否已开始采集 */
    @Volatile
    private var isCapturing = false

    /** 连续黑帧计数器 */
    private var blackFrameCount = 0

    /** 节流控制：上一次采集时间 */
    private var lastCaptureTime = 0L

    /** 录屏前台服务是否已启动 */
    private var isForegroundStarted = false

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createNotificationChannel()
        startBackgroundThread()

        Log.d(TAG, "录屏服务已创建: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 服务被系统重建但无 Intent → 尝试恢复
            Log.w(TAG, "服务重建，无 Intent 数据")
            return START_NOT_STICKY
        }

        try {
            when (intent.action) {
                ACTION_START_CAPTURE -> {
                    // 获取 MediaProjection Intent
                    val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT)
                    }

                    // 获取选区矩形
                    val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_SELECTION_RECT, Rect::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_SELECTION_RECT)
                    }

                    if (projectionIntent != null) {
                        startCapture(projectionIntent, rect)
                    } else {
                        // 没有 MediaProjection Intent → 尝试自动切回无障碍模式
                        Log.w(TAG, "缺少 MediaProjection Intent，切换到无障碍模式")
                        switchToAccessibilityMode()
                        stopSelf()
                    }
                }

                ACTION_CAPTURE_ONCE -> {
                    // 单次截图请求（用于 OCR 识别后重新截图）
                    captureOnce()
                }

                ACTION_STOP -> {
                    stopCapture()
                    stopSelf()
                }

                /**
                 * 预启动前台服务（通知栏可见），不创建 MediaProjection。
                 * 流程：弹出系统录屏授权对话框前调用此动作，确保通知栏显示"录屏搜题运行中"。
                 */
                ACTION_PREPARE_CAPTURE -> {
                    // 启动前台通知（通知栏可见）
                    if (!isForegroundStarted) {
                        startForegroundNotification()
                    }
                    // 初始化 OCR 引擎
                    if (!PaddleOCREngine.isReady()) {
                        PaddleOCREngine.init(this)
                    }
                    Log.d(TAG, "录屏前台服务已启动，等待用户授权...")
                }

                /**
                 * 设置 MediaProjection 授权 Intent（用户授权后调用）。
                 * 从授权 Intent 中解析出 MediaProjection，创建 VirtualDisplay 开始采集。
                 */
                ACTION_SET_PROJECTION -> {
                    val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT)
                    }

                    val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_SELECTION_RECT, Rect::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_SELECTION_RECT)
                    }

                    if (projectionIntent != null) {
                        startCapture(projectionIntent, rect)
                    } else {
                        Log.w(TAG, "ACTION_SET_PROJECTION: 缺少 MediaProjection Intent")
                        switchToAccessibilityMode()
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand 异常: ${e.message}", e)
            // 发生异常时确保前台通知已发出，避免系统判定为"未在时限内启动前台服务"
            if (!isForegroundStarted) {
                try {
                    startForegroundNotification()
                } catch (_: Exception) {
                    // 无法启动前台通知，安全停止服务
                }
            }
            // 尝试优雅降级：切换到无障碍模式
            try {
                switchToAccessibilityMode()
            } catch (_: Exception) { }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        stopBackgroundThread()
        instance = null
        Log.d(TAG, "录屏服务已销毁")
    }

    // ==================== 前台通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "录屏搜题",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "录屏搜题服务运行中"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        if (isForegroundStarted) return

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("录屏搜题运行中")
                .setContentText("正在识别屏幕内容...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("录屏搜题运行中")
                .setContentText("正在识别屏幕内容...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }

        // Android 14+ 必须指定 foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
    }

    // ==================== 后台线程 ====================

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    // ==================== 开始采集 ====================

    /**
     * 启动录屏采集。
     *
     * @param projectionIntent MediaProjection 授权 Intent（来自 Activity#onActivityResult）
     * @param rect 用户选区矩形（屏幕坐标），可为 null（后续通过 updateSelectionRect 设置）
     */
    private fun startCapture(projectionIntent: Intent, rect: Rect?) {
        if (isCapturing) {
            Log.w(TAG, "录屏采集已在进行中，忽略重复请求")
            return
        }

        this.selectionRect = rect

        // 启动前台服务
        startForegroundNotification()

        // 标记录屏权限已授予
        PermissionManager.markScreenCaptureGranted(this)

        // 创建 MediaProjection
        mediaProjection = mediaProjectionManager?.getMediaProjection(
            Activity.RESULT_OK,
            projectionIntent
        )

        if (mediaProjection == null) {
            Log.e(TAG, "无法获取 MediaProjection")
            switchToAccessibilityMode()
            stopSelf()
            return
        }

        // 注册 MediaProjection 停止回调
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 已停止（权限被回收或页面拦截）")
                mainHandler.post {
                    switchToAccessibilityMode()
                    stopSelf()
                }
            }
        }, mainHandler)

        // 创建 ImageReader（用于接收屏幕帧）
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2 // 缓冲队列大小
        ).apply {
            setOnImageAvailableListener({ reader ->
                onImageAvailable(reader)
            }, backgroundHandler)
        }

        // 创建 VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        isCapturing = true
        blackFrameCount = 0
        Log.d(TAG, "录屏采集已启动")
    }

    // ==================== 图像帧处理 ====================

    /**
     * ImageReader 回调：处理每一帧屏幕图像。
     *
     * 节流策略：200ms 内只处理一帧，避免高频 OCR 消耗资源。
     */
    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CAPTURE_INTERVAL_MS) {
            // 节流：跳过此帧，但仍需 close image
            val image = reader.acquireLatestImage()
            image?.close()
            return
        }
        lastCaptureTime = now

        val image: Image = reader.acquireLatestImage() ?: return

        try {
            processImage(image)
        } catch (e: Exception) {
            Log.e(TAG, "处理图像帧异常", e)
        } finally {
            image.close()
        }
    }

    /**
     * 处理单帧图像：裁剪选区 → 检查黑帧 → OCR 识别 → 结果分发。
     */
    private fun processImage(image: Image) {
        val bitmap = imageToBitmap(image) ?: return

        // 检查是否为黑帧（FLAG_SECURE 页面检测）
        if (isBlackFrame(bitmap)) {
            blackFrameCount++
            Log.d(TAG, "检测到黑帧 ($blackFrameCount/$BLACK_FRAME_THRESHOLD)")

            if (blackFrameCount >= BLACK_FRAME_THRESHOLD) {
                Log.w(TAG, "连续黑帧超过阈值，判定为 FLAG_SECURE 页面，切换到无障碍模式")
                bitmap.recycle()
                mainHandler.post {
                    // 弹窗提示用户
                    FloatWindowManager.showAnswerWindow(
                        this,
                        answer = "当前页面无法截屏",
                        explanation = "检测到页面启用了防截屏保护（FLAG_SECURE），\n" +
                                "已自动切换到无障碍模式，请重新选题。",
                        onDismissed = {
                            switchToAccessibilityMode()
                            stopSelf()
                        }
                    )
                }
                return
            }
        } else {
            // 非黑帧，重置计数器
            blackFrameCount = 0
        }

        // 获取选区矩形
        val rect = selectionRect
        if (rect == null) {
            Log.w(TAG, "选区矩形未设置，无法裁剪")
            bitmap.recycle()
            return
        }

        // 调用 PaddleOCR 识别
        PaddleOCREngine.recognize(bitmap, rect, object : PaddleOCREngine.RecognitionCallback {
            override fun onSuccess(text: String) {
                Log.d(TAG, "OCR 识别成功: ${text.take(50)}...")
                // 与无障碍共用同一套题库检索逻辑
                val answer = QuestionBankSearcher.search(this@ScreenCaptureService, text)
                mainHandler.post {
                    FloatWindowManager.showAnswerWindow(
                        this@ScreenCaptureService,
                        answer = answer,
                        explanation = "OCR 识别结果：${text.take(80)}..."
                    )
                }
                bitmap.recycle()
            }

            override fun onFailure(error: String) {
                Log.e(TAG, "OCR 识别失败: $error")
                mainHandler.post {
                    FloatWindowManager.showAnswerWindow(
                        this@ScreenCaptureService,
                        answer = "OCR 识别失败",
                        explanation = "错误信息：$error\n\n" +
                                "建议切换到无障碍模式重试。"
                    )
                }
                bitmap.recycle()
            }

            override fun onEmpty(screenshotPath: String?) {
                Log.d(TAG, "OCR 识别为空，截图已保存: $screenshotPath")
                mainHandler.post {
                    FloatWindowManager.showAnswerWindow(
                        this@ScreenCaptureService,
                        answer = "未识别到文字内容",
                        explanation = "所选区域内未检测到文字。\n\n" +
                                "可能原因：\n" +
                                "1. 选区未覆盖有效文字区域\n" +
                                "2. 文字颜色与背景对比度不足\n" +
                                "3. 页面使用了特殊字体\n\n" +
                                "Debug 截图已保存至：\n" +
                                "${screenshotPath ?: "保存失败"}\n\n" +
                                "建议：重新选题或切换到无障碍模式。",
                        onDismissed = {
                            // 重新打开选题框，让用户重新选择
                            FloatWindowManager.showSelectOverlay(
                                this@ScreenCaptureService
                            ) { newRect ->
                                updateSelectionRect(newRect)
                                triggerCaptureOnce()
                            }
                        }
                    )
                }
                bitmap.recycle()
            }
        })
    }

    /**
     * 将 Image 转换为 Bitmap。
     *
     * Image 格式为 RGBA_8888，直接读取 Plane[0] 的像素数据。
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - image.width * pixelStride

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 如果行填充不为 0，裁剪到实际宽度
        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    /**
     * 判断 Bitmap 是否为黑帧（FLAG_SECURE 页面检测）。
     *
     * 策略：采样计算平均亮度，低于阈值判为黑帧。
     */
    private fun isBlackFrame(bitmap: Bitmap): Boolean {
        // 降采样：每 10 个像素采样一个，减少计算量
        val sampleStep = 10
        var totalLuminance = 0.0
        var sampleCount = 0

        val sampleHeight = bitmap.height / sampleStep
        val sampleWidth = bitmap.width / sampleStep

        // 只采样部分区域
        val sampledPixels = IntArray(sampleWidth * sampleHeight)
        val targetBitmap = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, false)
        targetBitmap.getPixels(sampledPixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        targetBitmap.recycle()

        for (pixel in sampledPixels) {
            // 计算亮度: Y = 0.299R + 0.587G + 0.114B
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalLuminance += 0.299 * r + 0.587 * g + 0.114 * b
            sampleCount++
        }

        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else 255.0
        return avgLuminance < BLACK_FRAME_LUMINANCE
    }

    // ==================== 公开 API ====================

    /**
     * 更新选区矩形（用户重新选题后调用）。
     */
    fun updateSelectionRect(rect: Rect) {
        this.selectionRect = rect
        Log.d(TAG, "选区已更新: $rect")
    }

    /**
     * 触发单次截图识别（用于外部调用，如用户手动点击"重新识别"按钮）。
     */
    fun triggerCaptureOnce() {
        if (!isCapturing) {
            Log.w(TAG, "录屏服务未在采集状态，无法截图")
            return
        }

        // 在后台线程中执行一次截图
        backgroundHandler?.post {
            captureOnce()
        }
    }

    /**
     * 内部单次截图（从 ImageReader 获取最新一帧并处理）。
     */
    private fun captureOnce() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            lastCaptureTime = 0 // 重置节流，确保本次处理
            processImage(image)
        } finally {
            image.close()
        }
    }

    /**
     * 停止录屏采集（释放所有资源）。
     */
    fun stopCapture() {
        isCapturing = false

        // 清除录屏授权标记
        PermissionManager.clearScreenCaptureGranted(this)

        // 停止 VirtualDisplay
        virtualDisplay?.release()
        virtualDisplay = null

        // 关闭 ImageReader
        imageReader?.close()
        imageReader = null

        // 停止 MediaProjection
        mediaProjection?.stop()
        mediaProjection = null

        // 停止前台服务
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }

        Log.d(TAG, "录屏采集已停止，所有资源已释放")
    }

    // ==================== 模式切换 ====================

    /**
     * 自动切换回无障碍模式。
     *
     * 触发场景：
     * 1. MediaProjection 权限被用户回收
     * 2. FLAG_SECURE 页面（银行、支付等防截屏页面）
     * 3. MediaProjection 启动失败
     */
    private fun switchToAccessibilityMode() {
        Log.d(TAG, "切换到无障碍模式")

        // 记录切换原因，通知用户
        val accessibilityService = AccessibilitySearchService.getInstance()
        if (accessibilityService != null) {
            // 无障碍服务仍在运行 → 直接切换
            FloatWindowManager.showSelectOverlay(this) { rect ->
                accessibilityService.setSelectionRect(rect)
            }
        } else {
            // 无障碍服务未运行 → 提示用户开启
            FloatWindowManager.showAnswerWindow(
                this,
                answer = "录屏模式已停止",
                explanation = "原因：录屏权限被回收或页面不支持截屏。\n\n" +
                        "请开启无障碍服务后继续使用搜题功能。\n" +
                        "设置路径：设置 → 无障碍 → 智能搜题"
            )
        }
    }
}