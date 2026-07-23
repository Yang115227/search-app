package com.smartsearch.app.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.parser.ExcelImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库管理页面 —— 按学科管理题库，支持多选、批量删除、重命名和导入。
 *
 * 功能：
 * - 顶部导航：左上角返回箭头，页面标题「题库管理」
 * - 题库条目列表：每条展示名称、总题数量，复选框多选
 * - 底部操作栏：导入Excel、重命名（单选时）、删除（单选时）、批量删除（选中≥1项时）
 * - 重命名和删除操作均有确认对话框
 * - 所有数据库操作均包含异常捕获与用户提示
 * - 导入完成后通过 refreshTrigger 自动刷新列表
 */

/** 题库条目数据类 */
private data class SubjectManageItem(
    val subject: String,
    val count: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankManageScreen(
    onBack: () -> Unit,
    onImportClick: (() -> Unit)? = null,
    /** 由父页面传入的刷新键，当父页面完成导入操作后自增此值以触发数据重载 */
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 题库列表数据
    var subjects by remember { mutableStateOf<List<SubjectManageItem>>(emptyList()) }
    var selectedSubjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // 重命名对话框
    var showRenameDialog by remember { mutableStateOf(false) }

    // 删除确认对话框
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isBatchDelete by remember { mutableStateOf(false) }

    // ==================== 数据加载 ====================

    // 数据库初始化失败标记：初始化失败时展示空白占位页，不渲染列表避免崩溃
    var dbInitFailed by remember { mutableStateOf(false) }

    // refreshTrigger 或 refreshKey 变化时重新加载数据（覆盖初始加载和操作后的刷新）
    LaunchedEffect(refreshTrigger, refreshKey) {
        isLoading = true
        dbInitFailed = false
        Log.d("【DB_LOG】", "题库管理 LaunchedEffect 开始: refreshTrigger=$refreshTrigger refreshKey=$refreshKey")
        try {
            // 数据库初始化可能在 IO 线程失败，捕获后展示占位页
            val db = try {
                QuizDatabase.getInstance(context).also {
                    Log.d("【DB_LOG】", "QuizDatabase 初始化成功")
                }
            } catch (e: Exception) {
                Log.e("【DB_LOG】", "数据库初始化异常: ${e.message}", e)
                dbInitFailed = true
                isLoading = false
                return@LaunchedEffect
            }
            var loadedItems = emptyList<SubjectManageItem>()
            var allCount = 0
            withContext(Dispatchers.IO) {
                try {
                    Log.d("【DB_LOG】", "开始 IO 线程查询数据库")
                    val allSubjects = db.questionDao().getAllSubjects()
                    Log.d("【DB_LOG】", "查询 getAllSubjects 结果: ${allSubjects.size} 条")
                    loadedItems = allSubjects.map { subject ->
                        SubjectManageItem(subject, db.questionDao().getCountBySubject(subject))
                    }
                    allCount = db.questionDao().getCount()
                    Log.d("【DB_LOG】", "查询完成: loadedItems=${loadedItems.size} totalCount=$allCount")
                } catch (e: Exception) {
                    Log.e("【DB_LOG】", "数据库查询异常: ${e.message}", e)
                }
            }
            // 状态修改必须在主线程
            Log.d("【DB_LOG】", "切换到主线程更新 UI: subjects 大小=${loadedItems.size}")
            subjects = loadedItems
            totalCount = allCount
            // 清理已不存在的选中项
            val validSubjects = loadedItems.map { it.subject }.toSet()
            selectedSubjects = selectedSubjects.filter { it in validSubjects }.toSet()

            // 清理之前导入时可能误导入的表头行脏数据
            try {
                ExcelImporter.cleanupHeaderRows(context)
            } catch (e: Exception) {
                Log.e("【DB_LOG】", "清理表头行脏数据异常: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e("【DB_LOG】", "加载题库数据异常: ${e.message}", e)
            dbInitFailed = true
            Toast.makeText(context, "加载题库数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    // ==================== 重命名对话框 ====================

    if (showRenameDialog) {
        val singleSelected = selectedSubjects.singleOrNull() ?: ""
        var inputText by remember(singleSelected) { mutableStateOf(singleSelected) }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名题库") },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("题库名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = inputText.trim()
                        if (newName.isNotBlank() && newName != singleSelected) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        QuizDatabase.getInstance(context)
                                            .questionDao()
                                            .updateSubject(singleSelected, newName)
                                    }
                                    Toast.makeText(context, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                                    selectedSubjects = setOf(newName)
                                    refreshTrigger++
                                } catch (e: Exception) {
                                    Log.e("QuestionBankManage", "重命名异常: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = inputText.isNotBlank() && inputText.trim() != singleSelected
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ==================== 删除确认对话框 ====================

    if (showDeleteConfirm) {
        val targetSubjects = if (isBatchDelete) {
            selectedSubjects.toList()
        } else {
            selectedSubjects.take(1).toList()
        }
        val targetCount = targetSubjects.sumOf { subject ->
            subjects.find { it.subject == subject }?.count ?: 0
        }

        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (isBatchDelete) "确认批量删除" else "确认删除") },
            text = {
                if (isBatchDelete) {
                    Text(
                        "确定要删除以下 ${targetSubjects.size} 个题库吗？\n" +
                                "此操作不可撤销，共 $targetCount 道题目将被删除。\n\n" +
                                targetSubjects.joinToString("\n") { "• $it" }
                    )
                } else {
                    Text(
                        "确定要删除「${targetSubjects.first()}」题库吗？\n" +
                                "此操作不可撤销，共 $targetCount 道题目将被删除。"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val db = QuizDatabase.getInstance(context)
                                    targetSubjects.forEach { subject ->
                                        db.questionDao().deleteBySubject(subject)
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    if (isBatchDelete) "已删除 ${targetSubjects.size} 个题库" else "已删除「${targetSubjects.first()}」题库",
                                    Toast.LENGTH_SHORT
                                ).show()
                                selectedSubjects = emptySet()
                                showDeleteConfirm = false
                                refreshTrigger++
                            } catch (e: Exception) {
                                Log.e("QuestionBankManage", "删除异常: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                showDeleteConfirm = false
                            }
                        }
                    }
                ) {
                    Text("删除", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ==================== 主界面 ====================

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题库管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4CAF50),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            // 底部操作栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 导入Excel按钮
                    // 注意：导入时请校验 Excel 文件格式，确保包含正确的列头（如：题目、选项、答案等）
                    OutlinedButton(
                        onClick = { onImportClick?.invoke() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("导入Excel", fontSize = 14.sp)
                    }

                    // 重命名按钮（仅选中一项时启用）
                    OutlinedButton(
                        onClick = { showRenameDialog = true },
                        enabled = selectedSubjects.size == 1,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("重命名", fontSize = 14.sp)
                    }

                    // 删除按钮（仅选中一项时启用）
                    Button(
                        onClick = {
                            isBatchDelete = false
                            showDeleteConfirm = true
                        },
                        enabled = selectedSubjects.size == 1,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除", fontSize = 14.sp)
                    }

                    // 批量删除按钮（选中一项及以上时显示）
                    if (selectedSubjects.isNotEmpty()) {
                        Button(
                            onClick = {
                                isBatchDelete = true
                                showDeleteConfirm = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("批量删除", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (dbInitFailed) {
            // 数据库初始化失败：展示空白占位页，不渲染列表避免崩溃
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "数据库加载失败",
                        fontSize = 18.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "请重启应用或检查存储空间",
                        fontSize = 14.sp,
                        color = Color(0xFF999999)
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { refreshTrigger++ },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("重试", fontSize = 14.sp)
                    }
                }
            }
        } else if (isLoading) {
            // 加载中（使用Text替代CircularProgressIndicator避免某些设备上的组件兼容性闪退）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "加载中...",
                    color = Color(0xFF4CAF50),
                    fontSize = 16.sp
                )
            }
        } else if (subjects.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无题库",
                        fontSize = 18.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "请先导入题目",
                        fontSize = 14.sp,
                        color = Color(0xFF999999)
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { onImportClick?.invoke() },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("导入题目", fontSize = 14.sp)
                    }
                }
            }
        } else {
            // 题库列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 统计信息 + 选中信息
                Text(
                    text = "共 ${subjects.size} 个题库，$totalCount 道题目" +
                            if (selectedSubjects.isNotEmpty()) "  |  已选 ${selectedSubjects.size} 项" else "",
                    fontSize = 13.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // 列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(subjects, key = { it.subject }) { item ->
                        SubjectManageCard(
                            subject = item.subject,
                            count = item.count,
                            isSelected = item.subject in selectedSubjects,
                            onClick = {
                                selectedSubjects = if (item.subject in selectedSubjects) {
                                    selectedSubjects - item.subject
                                } else {
                                    selectedSubjects + item.subject
                                }
                            }
                        )
                    }
                    // 底部留白（底部操作栏高度）
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/**
 * 题库管理条目卡片。
 * - 包含复选框支持多选
 * - 选中时蓝色边框高亮
 * - 显示题库名称和题目数量
 */
@Composable
private fun SubjectManageCard(
    subject: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFFF0F7FF) else Color.White
    val borderColor = if (isSelected) Color(0xFF2196F3) else Color(0xFFE0E0E0)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：复选框 + 名称
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF2196F3)
                    )
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = subject,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFF1565C0) else Color(0xFF333333)
                    )
                    if (isSelected) {
                        Text(
                            text = "已选中",
                            fontSize = 12.sp,
                            color = Color(0xFF2196F3)
                        )
                    }
                }
            }

            // 右侧：题目数量
            Text(
                text = "${count}道",
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF2196F3) else Color(0xFF999999),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}