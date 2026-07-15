package com.smartsearch.app.feature.search.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PaddleOCR 离线识别引擎封装。
 *
 * # 架构说明
 * 对 PaddleOCR（com.baidu.paddle.lite.onnxocr）的轻量封装，提供统一的
 * 初始化、识别、资源释放接口，隔离底层 OCR 库的 API 变更。
 *
 * # 集成方式
 * 1. 在 `app/libs/` 放置 PaddleOCR 的 AAR 或 JAR
 * 2. 在 `app/build.gradle.kts` 添加依赖：
 *    ```kotlin
 *    implementation(files("libs/PaddleOCR.aar"))
 *    ```
 * 3. 将模型文件（det、cls、rec、dict）放入 `app/src/main/assets/ocr/`
 * 4. 调用 `PaddleOCREngine.init(context)` 初始化
 * 5. 调用 `PaddleOCREngine.recognize(bitmap, cropRect)` 识别
 *
 * # 模型文件目录结构
 * ```
 * app/src/main/assets/ocr/
 * ├── ch_ppocr_mobile_v2.0_det_opt.nb   # 检测模型
 * ├── ch_ppocr_mobile_v2.0_cls_opt.nb   # 方向分类模型
 * ├── ch_ppocr_mobile_v2.0_rec_opt.nb   # 识别模型
 * └── ppocr_keys_v1.txt                  # 字典文件
 * ```
 *
 * # 兼容性
 * Android 10 (API 29) ~ Android 14 (API 34)，无额外第三方依赖（除 PaddleOCR 本身）。
 */
object PaddleOCREngine {

    private const val TAG = "PaddleOCREngine"

    /** 是否已初始化 */
    @Volatile
    private var isInitialized = false

    /** 上下文引用 */
    private var appContext: Context? = null

    /** 上一次识别的文本结果缓存 */
    @Volatile
    private var lastRecognizedText: String = ""

    /** 识别结果回调 */
    interface RecognitionCallback {
        /** 识别成功，返回文本 */
        fun onSuccess(text: String)

        /** 识别失败，返回异常信息 */
        fun onFailure(error: String)

        /** 识别结果为空（图片中未检测到文字），返回保存的截图路径 */
        fun onEmpty(screenshotPath: String?)
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 OCR 引擎，加载模型文件。
     *
     * 推荐在 Application.onCreate 或首次使用前调用。
     * 初始化是耗时操作（约 1~3 秒），应在子线程执行。
     *
     * @param context Application 上下文
     * @return true 表示初始化成功
     */
    fun init(context: Context): Boolean {
        if (isInitialized) return true

        appContext = context.applicationContext

        return try {
            // TODO: 接入 PaddleOCR 实际初始化逻辑
            // 示例：
            // OcrEngine.init(
            //     context,
            //     "ocr/ch_ppocr_mobile_v2.0_det_opt.nb",
            //     "ocr/ch_ppocr_mobile_v2.0_cls_opt.nb",
            //     "ocr/ch_ppocr_mobile_v2.0_rec_opt.nb",
            //     "ocr/ppocr_keys_v1.txt"
            // )
            isInitialized = true
            Log.d(TAG, "PaddleOCR 引擎初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PaddleOCR 引擎初始化失败", e)
            isInitialized = false
            false
        }
    }

    /**
     * 判断引擎是否已初始化。
     */
    fun isReady(): Boolean = isInitialized

    // ==================== 识别 ====================

    /**
     * 对指定 Bitmap 的裁剪区域进行 OCR 识别。
     *
     * 内部流程：
     * 1. 根据 [cropRect] 从 [fullBitmap] 中裁剪出选区子图
     * 2. 将子图送入 PaddleOCR 进行文字检测 + 识别
     * 3. 检测到文字 → 回调 [RecognitionCallback.onSuccess]
     * 4. 未检测到文字 → 自动保存截图到 debug 目录 → 回调 [RecognitionCallback.onEmpty]
     * 5. 识别异常 → 回调 [RecognitionCallback.onFailure]
     *
     * @param fullBitmap 全屏截图（或录屏帧）
     * @param cropRect 用户选区矩形（屏幕坐标），对 fullBitmap 裁剪
     * @param callback 识别结果回调（主线程安全）
     */
    fun recognize(
        fullBitmap: Bitmap,
        cropRect: Rect,
        callback: RecognitionCallback
    ) {
        if (!isInitialized) {
            callback.onFailure("OCR 引擎未初始化，请先调用 PaddleOCREngine.init()")
            return
        }

        if (fullBitmap.isRecycled) {
            callback.onFailure("Bitmap 已回收，无法识别")
            return
        }

        try {
            // 第一步：裁剪选区
            val cropLeft = cropRect.left.coerceIn(0, fullBitmap.width - 1)
            val cropTop = cropRect.top.coerceIn(0, fullBitmap.height - 1)
            val cropRight = cropRect.right.coerceIn(cropLeft + 1, fullBitmap.width)
            val cropBottom = cropRect.bottom.coerceIn(cropTop + 1, fullBitmap.height)

            val cropWidth = cropRight - cropLeft
            val cropHeight = cropBottom - cropTop

            if (cropWidth <= 0 || cropHeight <= 0) {
                callback.onFailure("裁剪区域无效: ($cropLeft, $cropTop) → ($cropRight, $cropBottom)")
                return
            }

            val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)

            // 第二步：送入 OCR 引擎识别
            val recognizedText = runOcrInternal(croppedBitmap)

            // 回收裁剪后的 Bitmap（原始 fullBitmap 由调用方管理）
            croppedBitmap.recycle()

            // 第三步：判断结果
            if (recognizedText.isBlank()) {
                // 识别为空 → 保存截图到 debug 目录
                val debugPath = saveDebugScreenshot(fullBitmap, cropRect)
                callback.onEmpty(debugPath)
            } else {
                lastRecognizedText = recognizedText
                callback.onSuccess(recognizedText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR 识别过程异常", e)
            callback.onFailure("OCR 识别异常: ${e.message}")
        }
    }

    /**
     * 实际调用 PaddleOCR 引擎进行识别。
     *
     * TODO: 替换为 PaddleOCR 的实际调用
     * 示例：
     * ```kotlin
     * val result = OcrEngine.run(croppedBitmap)
     * return result.text
     * ```
     */
    private fun runOcrInternal(bitmap: Bitmap): String {
        // TODO: 接入 PaddleOCR 实际识别逻辑
        // 示例伪代码：
        // val predictor = PaddleOCR Predictor.getInstance()
        // val result = predictor.run(bitmap)
        // return result.map { it.text }.joinToString("\n")

        Log.d(TAG, "OCR 识别: ${bitmap.width}x${bitmap.height}")
        return "" // 占位：实际接入后替换
    }

    // ==================== Debug 截图保存 ====================

    /**
     * 将识别为空的截图保存到 debug 目录，用于后续分析。
     *
     * 保存路径：`/data/data/com.smartsearch.app/cache/debug_capture/yyyyMMdd_HHmmss_SSS.png`
     *
     * @param fullBitmap 全屏截图
     * @param cropRect 选区矩形（用于日志记录）
     * @return 保存的文件路径，失败返回 null
     */
    private fun saveDebugScreenshot(fullBitmap: Bitmap, cropRect: Rect): String? {
        return try {
            val context = appContext ?: return null

            val debugDir = File(context.cacheDir, "debug_capture")
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(Date())
            val fileName = "${timestamp}_${cropRect.left}_${cropRect.top}_${cropRect.width()}x${cropRect.height()}.png"
            val outputFile = File(debugDir, fileName)

            FileOutputStream(outputFile).use { fos ->
                fullBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }

            Log.d(TAG, "Debug 截图已保存: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存 Debug 截图失败", e)
            null
        }
    }

    // ==================== 清理 debug 截图 ====================

    /**
     * 清理 debug 截图目录，删除超过 [maxAgeDays] 天的旧文件。
     * 可在 Application.onCreate 或定期任务中调用，防止缓存堆积。
     *
     * @param maxAgeDays 文件保留天数，默认 3 天
     */
    fun cleanDebugScreenshots(maxAgeDays: Int = 3) {
        try {
            val context = appContext ?: return
            val debugDir = File(context.cacheDir, "debug_capture")
            if (!debugDir.exists()) return

            val cutoffTime = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
            debugDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    Log.d(TAG, "已清理过期 debug 截图: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理 debug 截图失败", e)
        }
    }

    // ==================== 资源释放 ====================

    /**
     * 释放 OCR 引擎资源。
     * 在不需要使用 OCR 时调用（如切换到无障碍模式）。
     */
    fun release() {
        if (!isInitialized) return
        try {
            // TODO: 调用 PaddleOCR 的资源释放
            // OcrEngine.release()
            isInitialized = false
            lastRecognizedText = ""
            Log.d(TAG, "PaddleOCR 引擎资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放 OCR 资源失败", e)
        }
    }

    /**
     * 获取上一次成功识别的文本（用于调试和日志）。
     */
    fun getLastRecognizedText(): String = lastRecognizedText
}