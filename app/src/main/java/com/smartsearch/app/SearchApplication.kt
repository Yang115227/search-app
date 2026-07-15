package com.smartsearch.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.feature.search.capture.PaddleOCREngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * App 全局 Application 入口。
 *
 * # 初始化顺序
 * 1. 通知渠道（Android 8.0+，Android 13+ 前台服务通知完整性校验）
 * 2. 文件 I/O 适配（Android 10+ 分区存储兼容）
 * 3. Room 数据库（懒加载，首次 DAO 调用时初始化）
 * 4. FloatWindowManager（悬浮窗生命周期管理）
 * 5. PaddleOCR 引擎（异步初始化，避免阻塞主线程）
 * 6. Debug 截图缓存目录清理
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)
 */
class SearchApplication : Application() {

    companion object {
        private const val TAG = "SearchApplication"

        // ── 通知渠道 ID（与各 Service 中保持一致） ──
        const val CHANNEL_FLOATING_WINDOW = "floating_window_channel"
        const val CHANNEL_SCREEN_CAPTURE = "screen_capture_channel"

        // ── Debug 截图缓存目录名 ──
        const val DEBUG_CAPTURE_DIR = "debug_capture"

        /**
         * 全局协程作用域（Application 级别，SupervisorJob 保证子协程异常不传播）。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 延迟获取 Application 实例（供工具类使用）。
         */
        @Volatile
        private lateinit var appInstance: SearchApplication

        fun getInstance(): SearchApplication = appInstance
    }

    override fun onCreate() {
        super.onCreate()
        appInstance = this

        // ── 第 1 步：通知渠道初始化 ──
        initNotificationChannels()

        // ── 第 2 步：文件 I/O 适配 ──
        initFileIOAdaptation()

        // ── 第 3 步：Room 数据库（懒加载触发） ──
        // 不在此处强制初始化，首次 DAO 调用时由 QuizDatabase.getInstance() 完成
        Log.d(TAG, "Room 数据库将在首次 DAO 调用时初始化")

        // ── 第 4 步：FloatWindowManager ──
        FloatWindowManager.init(this)

        // ── 第 5 步：PaddleOCR 异步初始化 ──
        initPaddleOCRAsync()

        // ── 第 6 步：Debug 截图缓存清理 ──
        cleanDebugCache()

        Log.d(TAG, "SearchApplication 初始化完成")
    }

    // ================================================
    // 通知渠道初始化（Android 8.0+ / Android 13+）
    // ================================================

    /**
     * 初始化所有通知渠道。
     *
     * ## Android 13+ (API 33) 适配
     * 从 Android 13 开始，前台服务通知的渠道重要性必须为 `IMPORTANCE_DEFAULT` 或更低，
     * 否则系统会抛出 `ForegroundServiceDidNotStartInTimeException`。
     *
     * ## Android 14+ (API 34) 适配
     * 前台服务必须声明 `foregroundServiceType`，已在 AndroidManifest.xml 中配置。
     * 通知渠道本身无额外变更。
     */
    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        // ── 悬浮球通知渠道 ──
        val floatingChannel = NotificationChannel(
            CHANNEL_FLOATING_WINDOW,
            "智能搜题",
            NotificationManager.IMPORTANCE_LOW // Android 13+ 必须 ≤ DEFAULT
        ).apply {
            description = "悬浮球搜题服务运行中"
            setShowBadge(false)
            // 不震动、不响铃
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(floatingChannel)

        // ── 录屏通知渠道 ──
        val captureChannel = NotificationChannel(
            CHANNEL_SCREEN_CAPTURE,
            "录屏搜题",
            NotificationManager.IMPORTANCE_LOW // Android 13+ 必须 ≤ DEFAULT
        ).apply {
            description = "录屏搜题服务运行中"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(captureChannel)

        Log.d(TAG, "通知渠道初始化完成: floating_window + screen_capture")
    }

    // ================================================
    // 文件 I/O 适配（Android 10+ 分区存储）
    // ================================================

    /**
     * 文件 I/O 适配。
     *
     * ## Android 10 (API 29) 分区存储
     * - `requestLegacyExternalStorage = true`（AndroidManifest 中声明）
     *   使应用在 Android 10 上仍可使用传统文件访问方式
     * - Android 11+ 此 flag 无效，必须使用 MediaStore 或 SAF
     *
     * ## Excel 导入 Uri 读取
     * - 使用 `ContentResolver.openInputStream(uri)` 读取
     * - 无需 `READ_EXTERNAL_STORAGE` 权限（使用 SAF 文件选择器）
     *
     * ## Debug 截图缓存
     * - 使用 `context.cacheDir`（应用私有缓存目录）
     * - 无需任何存储权限
     * - 系统可能自动清理，因此重要截图应迁移到 `filesDir`
     */
    private fun initFileIOAdaptation() {
        // 确保 Debug 截图缓存目录存在
        val debugDir = File(cacheDir, DEBUG_CAPTURE_DIR)
        if (!debugDir.exists()) {
            val created = debugDir.mkdirs()
            Log.d(TAG, "Debug 截图目录创建${if (created) "成功" else "失败"}: ${debugDir.absolutePath}")
        }

        // Android 10 分区存储日志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Android 10+ 分区存储已启用，requestLegacyExternalStorage=true")
        }

        // Android 11+ 分区存储强制启用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "Android 11+ 分区存储强制启用，文件访问使用 SAF/MediaStore")
        }
    }

    // ================================================
    // PaddleOCR 异步初始化
    // ================================================

    /**
     * 在后台线程初始化 PaddleOCR 引擎。
     *
     * 初始化耗时约 1~3 秒（加载模型文件），不阻塞主线程。
     * 如果初始化失败，录屏搜题会自动降级为无障碍模式。
     */
    private fun initPaddleOCRAsync() {
        applicationScope.launch {
            try {
                val success = PaddleOCREngine.init(this@SearchApplication)
                if (success) {
                    Log.d(TAG, "PaddleOCR 引擎初始化成功")
                } else {
                    Log.w(TAG, "PaddleOCR 引擎初始化失败，录屏模式将不可用")
                }
            } catch (e: Exception) {
                Log.e(TAG, "PaddleOCR 初始化异常", e)
            }
        }
    }

    // ================================================
    // Debug 缓存清理
    // ================================================

    /**
     * 清理过期的 Debug 截图缓存。
     *
     * 在 Application 启动时执行，删除 3 天前的截图文件。
     */
    private fun cleanDebugCache() {
        applicationScope.launch {
            try {
                PaddleOCREngine.cleanDebugScreenshots(maxAgeDays = 3)
                Log.d(TAG, "Debug 截图缓存清理完成")
            } catch (e: Exception) {
                Log.e(TAG, "Debug 缓存清理失败", e)
            }
        }
    }

    // ================================================
    // 工具方法
    // ================================================

    /**
     * 获取 Debug 截图缓存目录。
     *
     * @return `/data/data/com.smartsearch.app/cache/debug_capture/`
     */
    fun getDebugCaptureDir(): File {
        return File(cacheDir, DEBUG_CAPTURE_DIR)
    }

    /**
     * 获取 Excel 导入临时目录。
     *
     * @return `/data/data/com.smartsearch.app/cache/excel_import/`
     */
    fun getExcelImportDir(): File {
        val dir = File(cacheDir, "excel_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 判断当前设备是否支持分区存储（Android 10+）。
     */
    fun isScopedStorageEnabled(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * 判断当前设备是否强制分区存储（Android 11+）。
     */
    fun isScopedStorageForced(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}