package com.smartsearch.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.feature.search.capture.PaddleOCREngine
import com.smartsearch.app.feature.search.capture.QuestionBankSearcher
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 相机扫描搜题 —— 拍照 OCR 识别并检索本地题库。
 *
 * 流程：
 * 1. 校验相机权限
 * 2. 显示 CameraX 预览
 * 3. 点击拍照按钮 → 捕获照片
 * 4. PaddleOCREngine 识别文字
 * 5. QuestionBankSearcher 检索本地题库
 * 6. 显示结果弹窗
 */
class CameraSearchActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraSearchActivity"
    }

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        outputDirectory = File(cacheDir, "camera_captures").apply { mkdirs() }

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = PreviewView(this).apply {
            // 占满全屏
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        // 拍照按钮（叠加在预览上方）
        val captureButton = android.widget.Button(this).apply {
            text = "拍照识别"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
            textSize = 16f
            // 圆角
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#FF9800"))
                cornerRadius = 24f
            }
        }

        // 关闭按钮
        val closeButton = android.widget.Button(this).apply {
            text = "✕"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textSize = 20f
        }

        // 根布局
        val rootLayout = android.widget.FrameLayout(this)
        rootLayout.addView(previewView)

        // 底部按钮容器
        val bottomBar = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.gravity = android.view.Gravity.BOTTOM
            params.setMargins(0, 0, 0, 48)
            layoutParams = params
        }
        captureButton.layoutParams = android.widget.FrameLayout.LayoutParams(
            200, 200, android.view.Gravity.CENTER
        )
        bottomBar.addView(captureButton)
        rootLayout.addView(bottomBar)

        // 顶部关闭按钮
        closeButton.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.START
        ).apply { setMargins(16, 48, 0, 0) }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)

        // 初始化 CameraX
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView)
            } catch (e: Exception) {
                Log.e(TAG, "相机初始化失败: ${e.message}", e)
                Toast.makeText(this, "相机初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))

        // 拍照按钮点击
        captureButton.setOnClickListener { takePhoto() }

        // 关闭按钮点击
        closeButton.setOnClickListener { finish() }
    }

    private fun bindCameraUseCases(previewView: PreviewView) {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "绑定相机用例失败: ${e.message}", e)
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory,
            "camera_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "照片已保存: ${photoFile.absolutePath}")
                    Toast.makeText(this@CameraSearchActivity, "正在识别...", Toast.LENGTH_SHORT).show()
                    // 在后台线程执行 OCR 识别
                    activityScope.launch {
                        recognizeAndSearch(photoFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    Toast.makeText(
                        this@CameraSearchActivity,
                        "拍照失败: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /**
     * OCR 识别并检索本地题库。
     */
    private suspend fun recognizeAndSearch(photoFile: File) {
        try {
            // 1. 加载照片
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(photoFile.absolutePath)
            }
            if (bitmap == null) {
                showResult("图片加载失败", "无法解析拍摄的照片，请重试")
                return
            }

            // 2. 旋转校正（CameraX 照片通常需要旋转）
            val rotatedBitmap = rotateBitmap(bitmap, 90f)

            // 3. 初始化 OCR（如果未初始化）
            if (!PaddleOCREngine.isReady()) {
                withContext(Dispatchers.IO) {
                    PaddleOCREngine.init(this@CameraSearchActivity)
                }
            }

            // 4. OCR 识别
            var recognizedText = ""
            val ocrDone = CompletableDeferred<Boolean>()

            PaddleOCREngine.recognize(
                rotatedBitmap,
                android.graphics.Rect(0, 0, rotatedBitmap.width, rotatedBitmap.height),
                object : PaddleOCREngine.RecognitionCallback {
                    override fun onSuccess(text: String) {
                        recognizedText = text
                        ocrDone.complete(true)
                    }

                    override fun onFailure(error: String) {
                        Log.e(TAG, "OCR 识别失败: $error")
                        recognizedText = ""
                        ocrDone.complete(false)
                    }

                    override fun onEmpty(screenshotPath: String?) {
                        recognizedText = ""
                        ocrDone.complete(false)
                    }
                }
            )

            // 等待 OCR 完成（超时 30 秒）
            withTimeout(30000L) {
                ocrDone.await()
            }

            // 回收 Bitmap
            rotatedBitmap.recycle()
            bitmap.recycle()

            if (recognizedText.isBlank()) {
                showResult("未识别到文字", "照片中未检测到文字，请确保拍摄清晰并包含题目内容")
                return
            }

            // 5. 检索本地题库
            val answer = withContext(Dispatchers.IO) {
                QuestionBankSearcher.search(this@CameraSearchActivity, recognizedText)
            }

            // 6. 显示结果
            showResult("识别结果", answer, recognizedText)

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OCR 识别超时", e)
            showResult("识别超时", "OCR 识别耗时过长，请重试")
        } catch (e: Exception) {
            Log.e(TAG, "扫描识别异常: ${e.message}", e)
            showResult("识别失败", "扫描识别异常: ${e.message}")
        }
    }

    /**
     * 旋转 Bitmap。
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 显示识别结果对话框。
     */
    private fun showResult(title: String, message: String, recognizedText: String? = null) {
        runOnUiThread {
            val sb = StringBuilder()
            sb.append(message)
            if (!recognizedText.isNullOrBlank()) {
                sb.append("\n\n【识别文本】\n")
                sb.append(recognizedText.take(200))
            }

            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("关闭") { _, _ -> finish() }
                .setNeutralButton("继续扫描") { _, _ ->
                    // 不关闭 Activity，继续拍照
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        activityScope.cancel()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) { }
    }
}