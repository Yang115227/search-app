package com.smartsearch.app.feature.search.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.PixelFormat

/**
 * 录屏单例管理类 —— 全局管理 MediaProjection、VirtualDisplay 实例的生命周期。
 *
 * 核心职责：
 * 1. 启动新录屏前先销毁旧实例，释放资源
 * 2. 提供统一的 getMediaProjection() 入口，避免重复创建
 * 3. 全链路异常捕获，杜绝闪退
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCaptureManager"

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    /** 屏幕参数缓存 */
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /** 是否正在使用中 */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * 初始化屏幕参数（需在 Service.onCreate 或 Activity.onCreate 中调用）。
     */
    fun initScreenParams(context: Context) {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    /**
     * 获取 MediaProjectionManager（安全转换）。
     */
    fun getProjectionManager(context: Context): MediaProjectionManager? {
        return try {
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        } catch (e: Exception) {
            Log.e(TAG, "获取 MediaProjectionManager 异常: ${e.message}", e)
            null
        }
    }

    /**
     * 创建/获取 MediaProjection 实例。
     * 如果已有旧实例，先销毁再创建。
     *
     * @param context 上下文
     * @param projectionIntent 用户授权后的 MediaProjection Intent
     * @return 新创建的 MediaProjection，失败返回 null
     */
    fun createMediaProjection(context: Context, projectionIntent: Intent): MediaProjection? {
        // 先销毁旧实例
        destroyMediaProjection()

        try {
            val manager = getProjectionManager(context) ?: return null
            val projection = manager.getMediaProjection(Activity.RESULT_OK, projectionIntent)
            if (projection == null) {
                Log.e(TAG, "getMediaProjection 返回 null")
                return null
            }
            // 注册停止回调
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 已停止")
                    isActive = false
                    mediaProjection = null
                }
            }, Handler(context.mainLooper))

            mediaProjection = projection
            Log.d(TAG, "MediaProjection 已创建")
            return projection
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaProjection 异常: ${e.message}", e)
            return null
        }
    }

    /**
     * 创建 VirtualDisplay 和 ImageReader。
     * 如果已有旧实例，先销毁再创建。
     *
     * @param projection MediaProjection 实例
     * @param onImageAvailable 图像帧可用回调
     * @param backgroundHandler 后台 Handler
     * @return 创建的 ImageReader，失败返回 null
     */
    fun createVirtualDisplay(
        projection: MediaProjection,
        onImageAvailable: (ImageReader) -> Unit,
        backgroundHandler: Handler
    ): ImageReader? {
        // 先销毁旧实例
        destroyVirtualDisplay()

        try {
            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            reader.setOnImageAvailableListener({ reader ->
                try {
                    onImageAvailable(reader)
                } catch (e: Exception) {
                    Log.e(TAG, "ImageReader 回调异常: ${e.message}", e)
                }
            }, backgroundHandler)

            val vd = projection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                backgroundHandler
            )

            if (vd == null) {
                Log.e(TAG, "createVirtualDisplay 返回 null")
                reader.close()
                return null
            }

            virtualDisplay = vd
            imageReader = reader
            isActive = true
            Log.d(TAG, "VirtualDisplay 已创建: ${screenWidth}x${screenHeight}")
            return reader
        } catch (e: Exception) {
            Log.e(TAG, "创建 VirtualDisplay 异常: ${e.message}", e)
            return null
        }
    }

    /**
     * 销毁 MediaProjection 实例。
     */
    private fun destroyMediaProjection() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止 MediaProjection 异常: ${e.message}", e)
        }
        mediaProjection = null
    }

    /**
     * 销毁 VirtualDisplay 和 ImageReader 实例。
     */
    private fun destroyVirtualDisplay() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 VirtualDisplay 异常: ${e.message}", e)
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 ImageReader 异常: ${e.message}", e)
        }
        imageReader = null
    }

    /**
     * 释放所有资源（MediaProjection + VirtualDisplay + ImageReader）。
     */
    fun releaseAll() {
        destroyVirtualDisplay()
        destroyMediaProjection()
        isActive = false
        Log.d(TAG, "所有录屏资源已释放")
    }

    /**
     * 获取当前 MediaProjection 实例（用于外部注册回调等）。
     */
    fun getMediaProjection(): MediaProjection? = mediaProjection

    /**
     * 获取当前 ImageReader 实例（用于外部触发截图）。
     */
    fun getImageReader(): ImageReader? = imageReader
}