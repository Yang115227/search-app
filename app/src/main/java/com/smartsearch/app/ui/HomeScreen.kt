package com.smartsearch.app.ui

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonParser
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.properties.Delegates
import com.smartsearch.app.core.permission.PermissionManager
import com.smartsearch.app.core.service.FloatingWindowService
import com.smartsearch.app.core.utils.LogExporter
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.parser.AnswerCleaner
import com.smartsearch.app.data.parser.ExcelImporter
import com.smartsearch.app.feature.search.floatview.PracticeDialog
import com.smartsearch.app.feature.search.floatview.QuestionBankDialog
import com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService
import com.smartsearch.app.feature.search.capture.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/**
 * 首页 —— 权限校验、悬浮球启动、模式切换的统一入口。
 *
 * # 页面布局
 * - 顶部：应用标题 + 题库统计
 * - 中部：权限状态卡片（悬浮窗 / 无障碍 / 录屏 / 相机）
 * - 底部：操作按钮（无障碍搜题 / 录屏搜题 / 题库导入 / 错题本）
 *
 * # 完整业务调用链
 * ```
 * 点击【无障碍搜题】按钮
 *   → 校验悬浮窗权限 → 未授权则跳转设置页
 *   → 校验无障碍权限 → 未授权则跳转设置页
 *   → startService(FloatingWindowService) 启动悬浮球
 *   → 点击悬浮球 → triggerSelectionSearch()
 *   → FloatWindowManager.showSelectOverlay() 显示选题框
 *   → 用户拖拽选区 → 回调 Rect
 *   → AccessibilitySearchService.setSelectionRect(rect) 执行扫描
 *   → QuestionBankSearcher.search() 检索题库
 *   → FloatWindowManager.showAnswerWindow() 显示答案弹窗
 * ```
 */
class HomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    // ==================== Activity Result Launchers ====================

    /** 通知权限请求（Android 13+） */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("【SCREEN_RECORD_LOG】", "notificationPermissionLauncher 回调: granted=$granted")
        if (granted) {
            // 通知权限已授予 → 继续录屏流程
            Log.d("【SCREEN_RECORD_LOG】", "通知权限已授权, 开始执行录屏流程")
            doScreenCaptureSearch()
        } else {
            Log.w("【SCREEN_RECORD_LOG】", "通知权限被拒绝, 停止录屏流程")
            Toast.makeText(this, "通知权限被拒绝，录屏搜题无法启动通知栏服务", Toast.LENGTH_LONG).show()
        }
    }

    /** 录屏授权超时标记 */
    private var screenCaptureTimedOut = false

    /** 标记是否正在等待悬浮窗权限授权（从设置页返回后自动检测） */
    private var pendingOverlayPermission = false

    /** 录屏授权结果回调 */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        screenCaptureTimedOut = false // 收到回调，取消超时标记
        Log.d("【SCREEN_RECORD_LOG】", "screenCaptureLauncher 回调: resultCode=${result.resultCode} (RESULT_OK=${Activity.RESULT_OK}) data=${result.data != null}")
        // 严格判断 resultCode：必须等于 RESULT_OK
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                Log.d("【SCREEN_RECORD_LOG】", "录屏授权成功(resultCode=RESULT_OK), 开始启动前台服务+初始化MediaProjection")
                // 第1步：启动前台服务（通知栏显示"录屏搜题运行中"）
                ScreenCaptureService.startForegroundOnly(this)
                Log.d("【SCREEN_RECORD_LOG】", "前台服务已启动，开始发送投影Intent")
                // 第2步：将授权Intent发送给录屏服务，创建MediaProjection
                ScreenCaptureService.setProjection(
                    this,
                    result.data!!,
                    null // 选区由后续选题框回调传入
                )
                Log.d("【SCREEN_RECORD_LOG】", "MediaProjection初始化和VirtualDisplay创建完成")
                // 第3步：延迟300ms后切换到录屏模式，显示选题框
                // 延迟原因：部分ROM在MediaProjection+前台服务同步启动瞬间会短暂阻塞顶层窗口渲染，
                // 延迟创建选区窗口可以有效规避此渲染冲突。
                Handler(Looper.getMainLooper()).postDelayed({
                    FloatWindowManager.showSelectOverlayForScreenCapture(this)
                    Toast.makeText(this, "录屏模式已启动，请框选题目区域", Toast.LENGTH_SHORT).show()
                    Log.d("【SCREEN_RECORD_LOG】", "录屏搜题启动全流程完成")
                }, 300)
            } catch (e: Exception) {
                Log.e("【SCREEN_RECORD_LOG】", "启动录屏服务异常: ${e.message}", e)
                Toast.makeText(this, "启动录屏失败，已切换到无障碍模式", Toast.LENGTH_LONG).show()
                // 降级到无障碍模式
                try { startAccessibilitySearch() } catch (_: Exception) { }
            }
        } else {
            // 用户拒绝或取消 → 无需停止服务（服务尚未启动），弹窗提示
            Log.w("【SCREEN_RECORD_LOG】", "录屏授权被拒绝或取消, resultCode=${result.resultCode}, 跳过服务启动")
            // 用户拒绝授权，弹窗提示而非静默处理
            Toast.makeText(this, "录屏权限未授权，无法使用录屏搜题", Toast.LENGTH_LONG).show()
        }
    }

    /** 文件选择结果回调 */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri: Uri = result.data!!.data!!
            // 先弹出分类选择对话框，再执行导入
            showSubjectPickerDialog(uri)
        }
    }

    /** 相机权限请求 */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 权限已授予 → 启动相机扫描
            launchCameraSearch()
        } else {
            Toast.makeText(this, "相机权限被拒绝，无法使用扫描搜题", Toast.LENGTH_LONG).show()
        }
    }

    /** 当前正在等待导入的 Uri（分类选择对话框选定后使用） */
    private var pendingImportUri: Uri? = null

    /** 题库管理页面刷新键，导入完成后自增触发数据重载 */
    private var importRefreshKey by Delegates.notNull<Int>()

    /**
     * 显示分类选择对话框，让用户选择或输入学科分类。
     * 选定后执行导入。
     */
    private fun showSubjectPickerDialog(uri: Uri) {
        pendingImportUri = uri

        // 在 IO 线程查询已有学科
        CoroutineScope(Dispatchers.Main).launch {
            existingSubjects = withContext(Dispatchers.IO) {
                QuizDatabase.getInstance(this@HomeActivity).questionDao().getAllSubjects()
            }

            val builder = android.app.AlertDialog.Builder(this@HomeActivity)
            builder.setTitle("选择学科分类")

            // 将已有学科 + "不分类" + "输入新分类" 组合为选项列表
            val items = mutableListOf<String>()
            items.add("不分类")
            items.addAll(existingSubjects)
            items.add("输入新分类...")

            builder.setItems(items.toTypedArray()) { _: android.content.DialogInterface, which: Int ->
                when {
                    which == 0 -> {
                        // 不分类 → 直接导入
                        importWithSubject(uri, "")
                    }
                    which == items.size - 1 -> {
                        // 输入新分类 → 弹出输入对话框
                        showNewSubjectDialog(uri)
                    }
                    else -> {
                        // 选择已有分类
                        importWithSubject(uri, existingSubjects[which - 1])
                    }
                }
            }
            builder.setNegativeButton("取消", null)
            builder.show()
        }
    }

    /** 已有学科列表（缓存） */
    private var existingSubjects: List<String> = emptyList()

    /**
     * 显示输入新分类名称的对话框。
     */
    private fun showNewSubjectDialog(uri: Uri) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入学科名称，如：数学、语文..."
            setText("")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("输入新分类")
            .setView(input)
            .setPositiveButton("确定") { dialogInterface: android.content.DialogInterface, _: Int ->
                val subject = input.text.toString().trim()
                importWithSubject(uri, subject)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 使用指定学科导入 Excel 文件。
     */
    private fun importWithSubject(
        uri: Uri,
        subject: String,
        importMode: AnswerCleaner.ImportMode = AnswerCleaner.ImportMode.STRICT
    ) {
        pendingImportUri = null
        Toast.makeText(
            this,
            if (subject.isNotBlank()) "正在导入「$subject」题库..."
            else "正在导入题库，请稍候...",
            Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.Main).launch {
            val result = ExcelImporter.importFromUri(
                this@HomeActivity,
                uri,
                defaultSubject = subject,
                importMode = importMode
            )
            when (result) {
                is ExcelImporter.ImportResult.Success -> {
                    val msg = buildString {
                        append("导入成功：${result.count} 条题目")
                        if (subject.isNotBlank()) append("（$subject）")
                        if (result.errorRows.isNotEmpty()) {
                            append("；${result.errorRows.size} 行跳过（行号: ${result.errorRows.joinToString(", ") { it.toString() }}）")
                        }
                    }
                    Toast.makeText(
                        this@HomeActivity,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()
                    // 通知题库管理页面刷新数据
                    importRefreshKey++
                }
                is ExcelImporter.ImportResult.Error -> {
                    val msg = buildString {
                        append("导入失败：${result.message}")
                        if (result.errorRows.isNotEmpty()) {
                            append("；异常行: ${result.errorRows.joinToString(", ")}")
                        }
                    }
                    Toast.makeText(
                        this@HomeActivity,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScreenCaptureIntent(intent)
        handleCameraSearchIntent(intent)
    }

    /**
     * 处理从悬浮球服务发起的录屏搜题请求。
     * 悬浮球点击「录屏搜题」→ 启动 HomeActivity → 在此方法中触发录屏授权流程。
     */
    private fun handleScreenCaptureIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(FloatingWindowService.EXTRA_START_SCREEN_CAPTURE, false) == true) {
            startScreenCaptureSearch()
        }
    }

    /**
     * 处理从悬浮球服务发起的相机扫描搜题请求。
     */
    private fun handleCameraSearchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(FloatingWindowService.EXTRA_START_CAMERA_SEARCH, false) == true) {
            startCameraSearch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 处理从悬浮球发起的录屏/相机搜题请求
        handleScreenCaptureIntent(intent)
        handleCameraSearchIntent(intent)

        // 初始化悬浮窗管理器
        FloatWindowManager.init(this)

        setContent {
            val navController = rememberNavController()
            importRefreshKey = 0

            MaterialTheme(
                colorScheme = lightColorScheme()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            onStartAccessibilitySearch = { startAccessibilitySearch() },
                            onStartScreenCaptureSearch = { startScreenCaptureSearch() },
                            onStartCameraSearch = { startCameraSearch() },
                            onImportQuestions = { openFilePicker() },
                            onOpenQuestionBank = { navController.navigate("question_bank_manage") },
                            onOpenPractice = { startPractice() },
                            onOpenWrongBook = { openWrongBook() },
                            onOpenPracticeList = {
                                navController.navigate("question_bank_list")
                            },
                            onExportLog = { exportLog() }
                        )
                    }
                    composable("question_bank_list") {
                        QuestionBankListScreen(
                            onBack = { navController.popBackStack() },
                            onStartPractice = { subject, mode ->
                                val encodedSubject = URLEncoder.encode(subject, "UTF-8")
                                navController.navigate("answer/$encodedSubject/$mode")
                            }
                        )
                    }
                    composable("question_bank_manage") {
                        QuestionBankManageScreen(
                            onBack = { navController.popBackStack() },
                            onImportClick = { openFilePicker() },
                            refreshKey = importRefreshKey
                        )
                    }
                    composable(
                        route = "answer/{subject}/{mode}",
                        arguments = listOf(
                            navArgument("subject") { type = NavType.StringType },
                            navArgument("mode") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val rawSubject = backStackEntry.arguments?.getString("subject") ?: ""
                        val subject = URLDecoder.decode(rawSubject, "UTF-8")
                        val mode = backStackEntry.arguments?.getString("mode") ?: "SEQUENTIAL"
                        AnswerScreen(
                            subject = subject,
                            mode = mode,
                            onBack = { navController.popBackStack() },
                            onFinish = { navController.popBackStack("question_bank_list", false) }
                        )
                    }
                }
            }
        }
    }

    // ==================== 生命周期监听 ====================

    override fun onResume() {
        super.onResume()
        // 从悬浮窗设置页面返回时自动检测权限
        if (pendingOverlayPermission) {
            pendingOverlayPermission = false
            Log.d("【SCREEN_RECORD_LOG】", "onResume: 检测到从悬浮窗设置页面返回")
            if (PermissionManager.checkFloatingWindow(this) == PermissionManager.PermissionStatus.GRANTED) {
                Log.d("【SCREEN_RECORD_LOG】", "悬浮窗权限已授权, 继续执行录屏流程")
                continueScreenCaptureAfterOverlayPermission()
            } else {
                Log.w("【SCREEN_RECORD_LOG】", "悬浮窗权限仍未授权, 停止录屏流程")
                Toast.makeText(this, "请先开启悬浮窗权限后再试", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 日志导出 ====================

    /**
     * 导出调试日志到文件并分享。
     */
    private fun exportLog() {
        LogExporter.exportAndShare(this)
    }

    // ==================== 无障碍搜题 ====================

    /**
     * 完整的无障碍搜题启动流程。
     *
     * 第 1 步：校验悬浮窗权限
     * 第 2 步：校验无障碍权限
     * 第 3 步：启动悬浮球服务
     */
    private fun startAccessibilitySearch() {
        // 切换悬浮球模式为无障碍
        FloatingWindowService.switchMode(FloatWindowManager.SearchMode.ACCESSIBILITY)

        // 第 1 步：悬浮窗权限
        val floatingStatus = PermissionManager.checkFloatingWindow(this)
        if (floatingStatus != PermissionManager.PermissionStatus.GRANTED) {
            showPermissionGuide("floating_window")
            startActivity(PermissionManager.getFloatingWindowSettingsIntent(this))
            return
        }

        // 第 2 步：无障碍权限
        if (!AccessibilitySearchService.isServiceEnabled(this)) {
            showPermissionGuide("accessibility")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 第 3 步：启动悬浮球服务
        startFloatingBallService()
    }

    /**
     * 显示权限引导 Toast。
     */
    private fun showPermissionGuide(permissionName: String) {
        val message = PermissionManager.getPermissionGuideMessage(permissionName)
        // 分段显示：第一行作为 Toast 提示，完整引导文本用于后续对话框
        val shortMessage = message.split("\n\n").firstOrNull() ?: message
        Toast.makeText(this, shortMessage, Toast.LENGTH_LONG).show()
    }

    // ==================== 录屏搜题 ====================

    /**
     * 录屏搜题启动流程。
     *
     * 执行顺序：
     * 第 1 步：校验悬浮窗权限（优先于其他权限，确保系统允许创建叠加窗口）
     *   - 未授权 → 跳转系统悬浮窗设置页，onResume 自动检测返回后继续
     * 第 2 步：校验通知权限（Android 13+）
     * 第 3 步：弹出系统录屏权限对话框
     * 第 4 步：授权回调中启动前台服务+初始化MediaProjection
     * 第 5 步：权限全部就绪后调用 showSelectOverlay 创建选区窗口
     */
    private fun startScreenCaptureSearch() {
        Log.d("【SCREEN_RECORD_LOG】", "startScreenCaptureSearch 入口: SDK=${Build.VERSION.SDK_INT} TIRAMISU=${Build.VERSION_CODES.TIRAMISU}")
        try {
            // 切换悬浮球模式为录屏
            Log.d("【SCREEN_RECORD_LOG】", "切换悬浮球模式为 SCREEN_CAPTURE")
            FloatingWindowService.switchMode(FloatWindowManager.SearchMode.SCREEN_CAPTURE)

            // 先清理所有悬浮窗状态（防止之前悬浮球残留的 SELECTING 状态）
            FloatWindowManager.destroyAll()

            // ── 第 1 步：校验悬浮窗权限（优先于其他所有权限） ──
            val floatingStatus = PermissionManager.checkFloatingWindow(this)
            Log.d("【SCREEN_RECORD_LOG】", "悬浮窗权限状态: $floatingStatus")
            if (floatingStatus != PermissionManager.PermissionStatus.GRANTED) {
                Log.d("【SCREEN_RECORD_LOG】", "悬浮窗权限未授权, 引导开启")
                showPermissionGuide("floating_window")
                pendingOverlayPermission = true
                startActivity(PermissionManager.getFloatingWindowSettingsIntent(this))
                return
            }

            // 第 2 步：继续后续权限校验（通知权限 → 录屏授权）
            continueScreenCaptureAfterOverlayPermission()
        } catch (e: Exception) {
            Log.e("【SCREEN_RECORD_LOG】", "startScreenCaptureSearch 异常: ${e.message}", e)
            Toast.makeText(this, "启动录屏搜题失败，请重试", Toast.LENGTH_LONG).show()
            // 降级到无障碍模式
            try {
                startAccessibilitySearch()
            } catch (e2: Exception) {
                Log.e("【SCREEN_RECORD_LOG】", "降级到无障碍模式也失败: ${e2.message}", e2)
            }
        }
    }

    /**
     * 悬浮窗权限已确认后，继续后续权限校验流程。
     *
     * 第 2 步：校验通知权限（Android 13+）
     * 第 3 步：所有权限就绪后执行录屏授权
     */
    private fun continueScreenCaptureAfterOverlayPermission() {
        Log.d("【SCREEN_RECORD_LOG】", "continueScreenCaptureAfterOverlayPermission 入口")
        // 第 2 步：校验通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
            Log.d("【SCREEN_RECORD_LOG】", "通知权限状态: $notifPerm (GRANTED=${PackageManager.PERMISSION_GRANTED})")
            if (notifPerm != PackageManager.PERMISSION_GRANTED) {
                Log.d("【SCREEN_RECORD_LOG】", "通知权限未授权, 发起权限请求")
                showPermissionGuide("notification")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            Log.d("【SCREEN_RECORD_LOG】", "通知权限已授权")
        } else {
            Log.d("【SCREEN_RECORD_LOG】", "Android 13以下, 跳过通知权限检查")
        }

        // 第 3 步：所有权限已通过，执行录屏授权
        Log.d("【SCREEN_RECORD_LOG】", "所有权限已通过, 执行录屏流程")
        doScreenCaptureSearch()
    }

    /**
     * 执行录屏搜题（通知权限和悬浮窗权限已通过校验）。
     * 流程调整：先弹出系统录屏授权弹窗，等待用户授权成功后，再启动前台服务+初始化MediaProjection。
     * 避免同时并发启动前台服务和唤起授权弹窗导致的时序竞争。
     */
    private fun doScreenCaptureSearch() {
        Log.d("【SCREEN_RECORD_LOG】", "doScreenCaptureSearch 入口")
        try {
            // 第 1 步：先停止已有的录屏服务实例，释放 MediaProjection/VirtualDisplay 资源
            try {
                Log.d("【SCREEN_RECORD_LOG】", "停止旧录屏实例")
                ScreenCaptureService.getInstance()?.stopCapture()
                val stopIntent = android.content.Intent(this, ScreenCaptureService::class.java)
                stopService(stopIntent)
                Log.d("【SCREEN_RECORD_LOG】", "旧录屏实例已停止")
            } catch (e: Exception) {
                Log.w("【SCREEN_RECORD_LOG】", "停止旧录屏实例异常: ${e.message}", e)
            }

            // 第 2 步：弹出系统录屏权限对话框（不提前启动前台服务，避免时序竞争）
            val projectionManager = getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE
            ) as? android.media.projection.MediaProjectionManager
            if (projectionManager != null) {
                Log.d("【SCREEN_RECORD_LOG】", "弹出系统录屏授权对话框")
                screenCaptureTimedOut = false
                // 设置超时检测：30秒后如果未收到回调，输出超时日志
                kotlinx.coroutines.MainScope().launch {
                    kotlinx.coroutines.delay(30000L)
                    if (!screenCaptureTimedOut) {
                        screenCaptureTimedOut = true
                        Log.e("【SCREEN_RECORD_LOG】", "录屏授权超时(30s): 用户未在30秒内完成授权操作")
                    }
                }
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                Log.d("【SCREEN_RECORD_LOG】", "录屏授权对话框已弹出，等待用户授权回调")
            } else {
                Log.e("【SCREEN_RECORD_LOG】", "无法获取 MediaProjectionManager")
                Toast.makeText(this, "设备不支持录屏功能", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("【SCREEN_RECORD_LOG】", "执行录屏搜题异常: ${e.message}", e)
            Toast.makeText(this, "启动录屏搜题失败，请重试", Toast.LENGTH_LONG).show()
            try { startAccessibilitySearch() } catch (_: Exception) { }
        }
    }

    // ==================== 相机扫描搜题 ====================

    /**
     * 相机扫描搜题启动流程。
     *
     * 第 1 步：校验悬浮窗权限
     * 第 2 步：校验相机权限
     * 第 3 步：切换悬浮球模式为相机扫描
     * 第 4 步：启动相机扫描 Activity
     */
    private fun startCameraSearch() {
        // 切换悬浮球模式为扫描
        FloatingWindowService.switchMode(FloatWindowManager.SearchMode.CAMERA)

        // 先清理所有悬浮窗状态
        FloatWindowManager.destroyAll()

        // 第 1 步：相机权限
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showPermissionGuide("camera")
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // 第 2 步：悬浮窗权限
        val floatingStatus = PermissionManager.checkFloatingWindow(this)
        if (floatingStatus != PermissionManager.PermissionStatus.GRANTED) {
            showPermissionGuide("floating_window")
            startActivity(PermissionManager.getFloatingWindowSettingsIntent(this))
            return
        }

        // 第 3 步：启动悬浮球服务
        startFloatingBallService()

        // 第 4 步：启动相机扫描 Activity
        launchCameraSearch()
    }

    /**
     * 启动相机扫描 Activity。
     */
    private fun launchCameraSearch() {
        try {
            val intent = Intent(this, CameraSearchActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动相机扫描失败: ${e.message}", e)
            Toast.makeText(this, "启动相机扫描失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 悬浮球服务 ====================

    /**
     * 启动悬浮球前台服务。
     */
    private fun startFloatingBallService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "悬浮球已启动，点击悬浮球开始搜题", Toast.LENGTH_SHORT).show()
    }

    // ==================== 练习模式 ====================

    /**
     * 打开练习模式对话框。
     * 支持随机练习和顺序练习，选择答案后提交查看正确答案。
     */
    private fun startPractice() {
        try {
            PracticeDialog(this).show()
        } catch (e: Exception) {
            Log.e(TAG, "启动练习失败: ${e.message}", e)
            android.widget.Toast.makeText(this, "启动练习失败，请重试", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 错题本 ====================

    /**
     * 打开错题本对话框。
     * 使用简单的 AlertDialog 显示未掌握的错题列表。
     */
    private fun openWrongBook() {
        CoroutineScope(Dispatchers.Main).launch {
            val db = QuizDatabase.getInstance(this@HomeActivity)
            val wrongQuestions = withContext(Dispatchers.IO) {
                db.wrongQuestionDao().getUnmasteredWrongQuestions()
            }

            if (wrongQuestions.isEmpty()) {
                android.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle("错题本")
                    .setMessage("暂无错题记录，继续加油！")
                    .setPositiveButton("确定", null)
                    .show()
                return@launch
            }

            // 构建错题列表（题目摘要 + 错误次数）
            val items = withContext(Dispatchers.IO) {
                wrongQuestions.mapNotNull { wq ->
                    val q = db.questionDao().findById(wq.questionId)
                    if (q != null) {
                        "${q.question.take(25)}${if (q.question.length > 25) "..." else ""} (错${wq.wrongCount}次)"
                    } else null
                }.toTypedArray()
            }

            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("错题本 (${wrongQuestions.size}题)")
                .setItems(items) { _: DialogInterface, which: Int ->
                    val wq = wrongQuestions[which]
                    showWrongQuestionDetail(wq.questionId, wq.wrongCount)
                }
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /**
     * 显示错题详情对话框。
     */
    private fun showWrongQuestionDetail(questionId: Long, wrongCount: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val q = withContext(Dispatchers.IO) {
                QuizDatabase.getInstance(this@HomeActivity).questionDao().findById(questionId)
            }
            if (q == null) {
                Toast.makeText(this@HomeActivity, "题目已被删除", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val message = "题目：${q.question}\n\n" +
                    "答案：${q.answer}\n\n" +
                    if (q.explanation.isNotBlank()) "解析：${q.explanation}\n\n" else "" +
                    "错误次数：$wrongCount 次"

            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("错题详情")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .setNeutralButton("标记已掌握") { _: DialogInterface, _: Int ->
                    CoroutineScope(Dispatchers.Main).launch {
                        withContext(Dispatchers.IO) {
                            QuizDatabase.getInstance(this@HomeActivity)
                                .wrongQuestionDao().markMastered(questionId)
                        }
                        Toast.makeText(this@HomeActivity, "已标记为掌握", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    // ==================== 题库导入 ====================

    /**
     * 打开文件选择器选择 Excel 文件。
     */
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            ))
        }
        filePickerLauncher.launch(intent)
    }
}

// ==================== Composable UI ====================

/**
 * 首页 UI 组件。
 */
@Composable
fun HomeScreen(
    onStartAccessibilitySearch: () -> Unit,
    onStartScreenCaptureSearch: () -> Unit,
    onStartCameraSearch: () -> Unit,
    onImportQuestions: () -> Unit,
    onOpenQuestionBank: () -> Unit,
    onOpenPractice: () -> Unit,
    onOpenWrongBook: () -> Unit,
    onOpenPracticeList: () -> Unit = onOpenPractice,
    onExportLog: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 标题区域 ──
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "智能搜题",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50)
        )
        Text(
            text = "悬浮窗搜题 · 无障碍 · OCR识别",
            fontSize = 14.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))

        // ── 搜题模式按钮 ──
        Text(
            text = "选择搜题模式",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 无障碍搜题按钮（绿色，与悬浮球「无」一致）
        Button(
            onClick = onStartAccessibilitySearch,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "🟢 无障碍搜题（推荐）",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 录屏搜题按钮（蓝色，与悬浮球「录」一致）
        Button(
            onClick = onStartScreenCaptureSearch,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2196F3)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "🔵 录屏搜题（OCR 识别）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 相机扫描搜题按钮（橙色，与悬浮球「扫」一致）
        Button(
            onClick = onStartCameraSearch,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF9800)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "🟠 扫描搜题（相机OCR识别）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 功能入口 ──
        Text(
            text = "更多功能",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 题库导入
            FunctionCard(
                title = "题库导入",
                subtitle = "Excel 导入",
                onClick = onImportQuestions,
                modifier = Modifier.weight(1f)
            )

            // 题库管理
            FunctionCard(
                title = "题库管理",
                subtitle = "查看/删除",
                onClick = onOpenQuestionBank,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 练习
            FunctionCard(
                title = "练习",
                subtitle = "顺序/随机出题",
                onClick = onOpenPracticeList,
                modifier = Modifier.weight(1f)
            )

            // 错题本
            FunctionCard(
                title = "错题本",
                subtitle = "复习错题",
                onClick = onOpenWrongBook,
                modifier = Modifier.weight(1f)
            )
        }

        // ── 日志导出 ──
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "调试工具",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onExportLog,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFF666666)
            )
        ) {
            Text(
                text = "导出调试日志",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 功能入口卡片。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FunctionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF333333)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}