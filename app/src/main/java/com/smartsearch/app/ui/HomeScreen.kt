package com.smartsearch.app.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartsearch.app.core.permission.PermissionManager
import com.smartsearch.app.core.service.FloatingWindowService
import com.smartsearch.app.core.service.FloatWindowManager
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.parser.ExcelImporter
import com.smartsearch.app.feature.search.floatview.PracticeDialog
import com.smartsearch.app.feature.search.floatview.QuestionBankDialog
import com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService
import com.smartsearch.app.feature.search.capture.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 录屏授权结果回调 */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                // 授权成功 → 启动录屏服务
                ScreenCaptureService.startWithProjection(
                    this,
                    result.data!!,
                    null // 选区由后续选题框回调传入
                )
                // 切换到录屏模式，显示选题框
                FloatWindowManager.showSelectOverlayForScreenCapture(this)
                Toast.makeText(this, "录屏模式已启动，请框选题目区域", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "启动录屏服务异常: ${e.message}", e)
                Toast.makeText(this, "启动录屏失败，已切换到无障碍模式", Toast.LENGTH_LONG).show()
                // 降级到无障碍模式
                startAccessibilitySearch()
            }
        } else {
            Toast.makeText(this, "录屏权限未授权，已保持无障碍模式", Toast.LENGTH_SHORT).show()
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

    /** 当前正在等待导入的 Uri（分类选择对话框选定后使用） */
    private var pendingImportUri: Uri? = null

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
    private fun importWithSubject(uri: Uri, subject: String) {
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
                defaultSubject = subject
            )
            when (result) {
                is ExcelImporter.ImportResult.Success -> {
                    Toast.makeText(
                        this@HomeActivity,
                        "导入成功：${result.count} 条题目" +
                                if (subject.isNotBlank()) "（$subject）" else "",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is ExcelImporter.ImportResult.Error -> {
                    Toast.makeText(
                        this@HomeActivity,
                        "导入失败：${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化悬浮窗管理器
        FloatWindowManager.init(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme()
            ) {
                HomeScreen(
                    onStartAccessibilitySearch = { startAccessibilitySearch() },
                    onStartScreenCaptureSearch = { startScreenCaptureSearch() },
                    onImportQuestions = { openFilePicker() },
                    onOpenQuestionBank = { QuestionBankDialog(this, onImportClick = { openFilePicker() }).show() },
                    onOpenPractice = { startPractice() },
                    onOpenWrongBook = { openWrongBook() },
                    permissionStatus = rememberPermissionStatus()
                )
            }
        }
    }

    // ==================== 权限状态 ====================

    /**
     * 组合四个权限状态，响应式刷新。
     */
    @Composable
    private fun rememberPermissionStatus(): Map<String, PermissionManager.PermissionStatus> {
        val context = LocalContext.current
        var status by remember {
            mutableStateOf(
                PermissionManager.getAllPermissions(
                    context,
                    AccessibilitySearchService::class.java.name,
                    ScreenCaptureService::class.java.name
                )
            )
        }

        // 监听 Activity 生命周期，每次 onResume 时刷新权限状态
        DisposableEffect(context) {
            val activity = context as? ComponentActivity ?: return@DisposableEffect onDispose { }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    status = PermissionManager.getAllPermissions(
                        context,
                        AccessibilitySearchService::class.java.name,
                        ScreenCaptureService::class.java.name
                    )
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
            }
        }

        return status
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
     * 第 1 步：校验悬浮窗权限
     * 第 2 步：校验录屏权限 → 启动系统授权对话框
     * 第 3 步：授权回调中启动 ScreenCaptureService
     */
    private fun startScreenCaptureSearch() {
        // 先清理所有悬浮窗状态（防止之前悬浮球残留的 SELECTING 状态）
        FloatWindowManager.destroyAll()

        // 第 1 步：悬浮窗权限
        val floatingStatus = PermissionManager.checkFloatingWindow(this)
        if (floatingStatus != PermissionManager.PermissionStatus.GRANTED) {
            showPermissionGuide("floating_window")
            startActivity(PermissionManager.getFloatingWindowSettingsIntent(this))
            return
        }

        // 第 2 步：启动录屏授权
        ScreenCaptureService.switchFromAccessibility(this) { projectionIntent ->
            // 启动系统录屏授权对话框
            screenCaptureLauncher.launch(projectionIntent)
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
        PracticeDialog(this).show()
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
    onImportQuestions: () -> Unit,
    onOpenQuestionBank: () -> Unit,
    onOpenPractice: () -> Unit,
    onOpenWrongBook: () -> Unit,
    permissionStatus: Map<String, PermissionManager.PermissionStatus>
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

        // ── 权限状态卡片 ──
        PermissionStatusCard(permissionStatus)
        Spacer(modifier = Modifier.height(16.dp))

        // ── 搜题模式按钮 ──
        Text(
            text = "选择搜题模式",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 无障碍搜题按钮（主按钮）
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
                text = "无障碍搜题（推荐）",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 录屏搜题按钮
        OutlinedButton(
            onClick = onStartScreenCaptureSearch,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "录屏搜题（OCR 识别）",
                fontSize = 14.sp,
                color = Color(0xFF4CAF50)
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
                subtitle = "随机出题",
                onClick = onOpenPractice,
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 权限状态卡片 —— 展示四项权限的授予状态。
 */
@Composable
fun PermissionStatusCard(status: Map<String, PermissionManager.PermissionStatus>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "权限状态",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 四项权限状态
            PermissionItem("悬浮窗", status["floating_window"])
            PermissionItem("无障碍", status["accessibility"])
            PermissionItem("录屏", status["screen_capture"])
            PermissionItem("相机", status["camera"])
        }
    }
}

/**
 * 单项权限状态行。
 */
@Composable
fun PermissionItem(name: String, status: PermissionManager.PermissionStatus?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, fontSize = 14.sp)

        val (text, color) = when (status) {
            PermissionManager.PermissionStatus.GRANTED -> "✓ 已授权" to Color(0xFF4CAF50)
            PermissionManager.PermissionStatus.DENIED -> "✗ 未授权" to Color(0xFFFF5722)
            PermissionManager.PermissionStatus.NOT_APPLICABLE -> "— 不可用" to Color.Gray
            null -> "— 未知" to Color.Gray
        }

        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
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