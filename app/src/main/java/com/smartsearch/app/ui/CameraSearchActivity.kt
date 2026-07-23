package com.smartsearch.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.smartsearch.app.feature.search.capture.PaddleOCREngine
import com.smartsearch.app.feature.search.capture.QuestionBankSearcher
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 相机扫描搜题 —— 实时 CameraX 预览 + 四角取景框 + 限频 OCR。
 *
 * 布局：
 * - 上半部分：CameraX 实时预览（60%），叠加四角取景框
 * - 下半部分：答案面板常驻可见（40%），不遮挡取景区域
 *
 * 功能：
 * - 四角取景框，支持捏合/拖边缘缩放
 * - 框外画面不参与识别，降低算力消耗
 * - 限频：每秒最多识别 1 帧
 * - 无相册导入，仅摄像头实时扫描
 */
class CameraSearchActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraSearch"
        /** OCR 限频间隔（毫秒） */
        private const val OCR_INTERVAL_MS = 1000L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var cropOverlay: CropOverlayView? = null
    private var answerText: TextView? = null
    private var scanningHint: TextView? = null
    private var answerPanel: View? = null

    /** 是否正在处理 OCR（限频用） */
    @Volatile
    private var isProcessing = false

    /** 最近一次识别结果文本 */
    @Volatile
    private var lastRecognizedText = ""

    /** 取景框在预览中的归一化矩形（0~1） */
    private var cropRectNormalized = RectF(0.2f, 0.15f, 0.8f, 0.55f)

    /** 拍照缓存目录 */
    private lateinit var outputDirectory: File

    /** 定时扫描 Handler */
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            takePhoto()
            scanHandler.postDelayed(this, 1000L)
        }
    }

    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 相机权限请求 */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "相机权限被拒绝，无法使用扫描搜题", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputDirectory = File(cacheDir, "camera_captures").apply { mkdirs() }

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initUI()
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initUI() {
        // ── 根布局：垂直方向 ──
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // ── 上半部分：相机预览区（60% 高度） ──
        val previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 0.6f }
        }

        // CameraX PreviewView
        previewView = PreviewView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        previewContainer.addView(previewView)

        // 四角取景框叠加层
        cropOverlay = CropOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setCropRect(cropRectNormalized)
            onCropChanged = { rect ->
                cropRectNormalized = rect
            }
        }
        previewContainer.addView(cropOverlay)

        // 关闭按钮（左上角）
        val closeBtn = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(16, 48, 0, 0) }
            setOnClickListener { finish() }
        }
        previewContainer.addView(closeBtn)

        rootLayout.addView(previewContainer)

        // ── 分割线 ──
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                2
            )
            setBackgroundColor(Color.parseColor("#FF9800"))
        }
        rootLayout.addView(divider)

        // ── 下半部分：答案面板（40% 高度） ──
        answerPanel = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 0.4f }
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        // 标题栏
        val titleRow = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleIcon = TextView(this).apply {
            text = "📷"
            textSize = 18f
        }
        titleRow.addView(titleIcon)

        val titleText = TextView(this).apply {
            text = "  扫描识别结果"
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleRow.addView(titleText)

        (answerPanel as LinearLayout).addView(titleRow)

        // 扫描提示
        scanningHint = TextView(this).apply {
            text = "正在扫描..."
            setTextColor(Color.parseColor("#999999"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        (answerPanel as LinearLayout).addView(scanningHint)

        // 识别结果文本
        answerText = TextView(this).apply {
            text = "等待识别..."
            setTextColor(Color.WHITE)
            textSize = 15f
            lineSpacing(4f, 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                topMargin = 8
                weight = 1f
            }
        }
        (answerPanel as LinearLayout).addView(answerText)

        // 底部提示
        val bottomHint = TextView(this).apply {
            text = "调整取景框对准题目区域，自动识别"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }
        (answerPanel as LinearLayout).addView(bottomHint)

        rootLayout.addView(answerPanel)

        setContentView(rootLayout)
    }

    private fun startCamera() {
        val previewView = previewView ?: return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // ImageCapture for periodic capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                // 更新扫描提示
                scanningHint?.text = "实时扫描中..."
                answerText?.text = "正在识别取景框内文字..."

                // 启动定时扫描（每秒 1 帧）
                scanHandler.post(scanRunnable)

            } catch (e: Exception) {
                Log.e(TAG, "相机初始化失败", e)
                Toast.makeText(this, "相机初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 拍照并 OCR 识别。
     * 限频：通过 isProcessing 标记确保每秒最多 1 次。
     */
    private fun takePhoto() {
        if (isProcessing) return
        isProcessing = true

        val imageCapture = imageCapture ?: run {
            isProcessing = false
            return
        }

        val photoFile = File(
            outputDirectory,
            "scan_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    captureAndRecognize(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    // 限频解锁，1 秒后重试
                    scanHandler.postDelayed({ isProcessing = false }, OCR_INTERVAL_MS)
                }
            }
        )
    }

    /**
     * 分析拍照结果：裁剪取景框区域 → OCR 识别 → 检索题库。
     */
    private fun captureAndRecognize(photoFile: File) {
        try {
            // 加载照片
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            if (bitmap == null) {
                isProcessing = false
                return
            }

            // 裁剪取景框区域
            val cropRect = calculateCropRect(bitmap.width, bitmap.height)
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                cropRect.left, cropRect.top,
                cropRect.width(), cropRect.height()
            )

            // 主线程更新提示
            runOnUiThread {
                scanningHint?.text = "正在识别..."
            }

            // 异步 OCR 识别
            analysisScope.launch {
                try {
                    // 确保 OCR 引擎已初始化
                    if (!PaddleOCREngine.isReady()) {
                        withContext(Dispatchers.IO) {
                            PaddleOCREngine.init(this@CameraSearchActivity)
                        }
                    }

                    // OCR 识别（使用 CompletableDeferred 等待结果）
                    var recognizedText = ""
                    val ocrDone = CompletableDeferred<Boolean>()

                    PaddleOCREngine.recognize(
                        croppedBitmap,
                        Rect(0, 0, croppedBitmap.width, croppedBitmap.height),
                        object : PaddleOCREngine.RecognitionCallback {
                            override fun onSuccess(text: String) {
                                recognizedText = text
                                ocrDone.complete(true)
                            }

                            override fun onFailure(error: String) {
                                Log.e(TAG, "OCR 失败: $error")
                                recognizedText = ""
                                ocrDone.complete(false)
                            }

                            override fun onEmpty(screenshotPath: String?) {
                                recognizedText = ""
                                ocrDone.complete(false)
                            }
                        }
                    )

                    // 等待 OCR 完成（超时 10 秒）
                    withTimeout(10000L) {
                        ocrDone.await()
                    }

                    if (recognizedText.isNotBlank()) {
                        lastRecognizedText = recognizedText

                        // 检索本地题库
                        val answer = withContext(Dispatchers.IO) {
                            QuestionBankSearcher.search(this@CameraSearchActivity, recognizedText)
                        }

                        // 更新 UI
                        runOnUiThread {
                            scanningHint?.text = "已识别"
                            answerText?.text = buildString {
                                append("【识别文本】\n")
                                append(recognizedText.take(200))
                                if (recognizedText.length > 200) append("...")
                                append("\n\n【检索结果】\n")
                                append(answer.take(500))
                            }
                        }
                    } else {
                        runOnUiThread {
                            scanningHint?.text = "扫描中..."
                            answerText?.text = "未在取景框内检测到文字，请调整取景框对准题目区域"
                        }
                    }

                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "OCR 超时", e)
                } catch (e: Exception) {
                    Log.e(TAG, "OCR 处理异常", e)
                } finally {
                    // 限频解锁
                    isProcessing = false
                }
            }

            // 回收 Bitmap
            croppedBitmap.recycle()
            bitmap.recycle()

            // 删除临时文件
            try { photoFile.delete() } catch (_: Exception) { }

        } catch (e: Exception) {
            Log.e(TAG, "拍照处理异常", e)
            isProcessing = false
        }
    }

    /**
     * 根据归一化取景框计算实际裁剪区域。
     */
    private fun calculateCropRect(bitmapWidth: Int, bitmapHeight: Int): Rect {
        return Rect(
            (cropRectNormalized.left * bitmapWidth).toInt().coerceIn(0, bitmapWidth),
            (cropRectNormalized.top * bitmapHeight).toInt().coerceIn(0, bitmapHeight),
            (cropRectNormalized.right * bitmapWidth).toInt().coerceIn(0, bitmapWidth),
            (cropRectNormalized.bottom * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scanHandler.removeCallbacks(scanRunnable)
        analysisScope.cancel()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) { }
    }

    // ==================== 四角取景框 View ====================

    /**
     * 四角取景框覆盖层。
     * - 框外区域半透明遮罩
     * - 四角白色高亮短角（相机取景框风格）
     * - 支持单指拖拽边角缩放、双指捏合缩放
     */
    class CropOverlayView(context: android.content.Context) : View(context) {

        /** 归一化取景框矩形 (0~1) */
        private var cropRect = RectF(0.2f, 0.15f, 0.8f, 0.55f)

        /** 取景框变化回调 */
        var onCropChanged: ((RectF) -> Unit)? = null

        /** 屏幕像素中的取景框 */
        private var pixelRect = RectF()

        // 画笔
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80000000.toInt()
            style = Paint.Style.FILL
        }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.FILL
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        // 触摸状态
        private var touchMode = 0 // 0=none, 1=drag, 2=resize_left, 3=resize_right, 4=resize_top, 5=resize_bottom
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var dragStartRect = RectF()
        private val touchSlop = 30f
        private val minSize = 0.1f

        fun setCropRect(rect: RectF) {
            cropRect.set(rect)
            invalidate()
        }

        fun getCropRect(): RectF = RectF(cropRect)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updatePixelRect()
        }

        private fun updatePixelRect() {
            pixelRect.set(
                cropRect.left * width,
                cropRect.top * height,
                cropRect.right * width,
                cropRect.bottom * height
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            updatePixelRect()

            val r = pixelRect

            // 离屏 Bitmap 镂空遮罩
            val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(r, clearPaint)
            canvas.restoreToCount(layerId)

            // 边框
            canvas.drawRect(r, borderPaint)

            // 四角
            val cornerLen = 40f
            // 左上
            canvas.drawLine(r.left, r.top, r.left + cornerLen, r.top, cornerPaint)
            canvas.drawLine(r.left, r.top, r.left, r.top + cornerLen, cornerPaint)
            // 右上
            canvas.drawLine(r.right - cornerLen, r.top, r.right, r.top, cornerPaint)
            canvas.drawLine(r.right, r.top, r.right, r.top + cornerLen, cornerPaint)
            // 左下
            canvas.drawLine(r.left, r.bottom - cornerLen, r.left, r.bottom, cornerPaint)
            canvas.drawLine(r.left, r.bottom, r.left + cornerLen, r.bottom, cornerPaint)
            // 右下
            canvas.drawLine(r.right - cornerLen, r.bottom, r.right, r.bottom, cornerPaint)
            canvas.drawLine(r.right, r.bottom - cornerLen, r.right, r.bottom, cornerPaint)

            // 四边中点小标记
            val midLen = 20f
            // 上边中点
            canvas.drawLine(r.centerX() - midLen, r.top, r.centerX() + midLen, r.top, cornerPaint)
            // 下边中点
            canvas.drawLine(r.centerX() - midLen, r.bottom, r.centerX() + midLen, r.bottom, cornerPaint)
            // 左边中点
            canvas.drawLine(r.left, r.centerY() - midLen, r.left, r.centerY() + midLen, cornerPaint)
            // 右边中点
            canvas.drawLine(r.right, r.centerY() - midLen, r.right, r.centerY() + midLen, cornerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = x
                    touchStartY = y
                    dragStartRect.set(pixelRect)
                    touchMode = detectTouchMode(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchMode == 0) return true
                    val dx = x - touchStartX
                    val dy = y - touchStartY
                    val dw = if (width > 0) dx / width else 0f
                    val dh = if (height > 0) dy / height else 0f

                    val newRect = RectF(cropRect)
                    when (touchMode) {
                        1 -> { // 拖动整体
                            newRect.left = (dragStartRect.left / width + dw).coerceIn(0f, 1f)
                            newRect.top = (dragStartRect.top / height + dh).coerceIn(0f, 1f)
                            newRect.right = (dragStartRect.right / width + dw).coerceIn(0f, 1f)
                            newRect.bottom = (dragStartRect.bottom / height + dh).coerceIn(0f, 1f)
                        }
                        2 -> { // 拖左边
                            newRect.left = (dragStartRect.left / width + dw).coerceIn(0f, cropRect.right - minSize)
                        }
                        3 -> { // 拖右边
                            newRect.right = (dragStartRect.right / width + dw).coerceIn(cropRect.left + minSize, 1f)
                        }
                        4 -> { // 拖上边
                            newRect.top = (dragStartRect.top / height + dh).coerceIn(0f, cropRect.bottom - minSize)
                        }
                        5 -> { // 拖下边
                            newRect.bottom = (dragStartRect.bottom / height + dh).coerceIn(cropRect.top + minSize, 1f)
                        }
                    }

                    // 双指缩放（捏合）
                    if (event.pointerCount >= 2) {
                        val pinchScale = calculatePinchScale(event)
                        val cx = cropRect.centerX()
                        val cy = cropRect.centerY()
                        val halfW = (cropRect.width() / 2f * pinchScale).coerceIn(minSize / 2f, 0.5f)
                        val halfH = (cropRect.height() / 2f * pinchScale).coerceIn(minSize / 2f, 0.5f)
                        newRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                    }

                    // 约束在屏幕内
                    if (newRect.width() >= minSize && newRect.height() >= minSize) {
                        cropRect.set(newRect)
                        onCropChanged?.invoke(cropRect)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchMode = 0
                }
            }
            return true
        }

        private fun detectTouchMode(x: Float, y: Float): Int {
            val r = pixelRect
            // 检测靠近哪条边
            val nearLeft = kotlin.math.abs(x - r.left) < touchSlop
            val nearRight = kotlin.math.abs(x - r.right) < touchSlop
            val nearTop = kotlin.math.abs(y - r.top) < touchSlop
            val nearBottom = kotlin.math.abs(y - r.bottom) < touchSlop

            return when {
                nearLeft -> 2
                nearRight -> 3
                nearTop -> 4
                nearBottom -> 5
                r.contains(x, y) -> 1
                else -> 0
            }
        }

        private fun calculatePinchScale(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 1f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            val newDist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (newDist < 10f) return 1f
            val oldDist = event.getHistoricalEvent(0)?.let { hist ->
                val hdx = hist.getX(0) - hist.getX(1)
                val hdy = hist.getY(0) - hist.getY(1)
                kotlin.math.sqrt((hdx * hdx + hdy * hdy).toDouble()).toFloat()
            } ?: newDist
            return if (oldDist > 0) newDist / oldDist else 1f
        }
    }
}