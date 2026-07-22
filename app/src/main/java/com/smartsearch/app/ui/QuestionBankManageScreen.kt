package com.smartsearch.app.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartsearch.app.data.local.QuizDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库管理页面 —— 按学科管理题库，支持删除和重命名。
 *
 * 功能：
 * - 顶部导航：左上角返回箭头，页面标题「题库管理」
 * - 题库条目列表：每条展示名称、总题数量
 * - 单选选中机制：选中条目蓝色边框高亮，同一时间只能选择一套题库
 * - 底部操作栏：删除（选中时）、重命名（选中时）
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
    onImportClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 题库列表数据
    var subjects by remember { mutableStateOf<List<SubjectManageItem>>(emptyList()) }
    var selectedSubject by remember { mutableStateOf("") }
    var totalCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    // 重命名对话框
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // 加载数据
    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                val db = QuizDatabase.getInstance(context)
                val (items, allCount) = withContext(Dispatchers.IO) {
                    val allSubjects = db.questionDao().getAllSubjects()
                    val itemList = allSubjects.map { subject ->
                        SubjectManageItem(subject, db.questionDao().getCountBySubject(subject))
                    }
                    val count = db.questionDao().getCount()
                    Pair(itemList, count)
                }
                subjects = items
                totalCount = allCount
                if (selectedSubject.isNotEmpty() && items.none { it.subject == selectedSubject }) {
                    selectedSubject = ""
                }
                isLoading = false
            } catch (e: Exception) {
                Log.e("QuestionBankManage", "加载题库数据异常: ${e.message}", e)
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    // ==================== 重命名对话框 ====================
    if (showRenameDialog) {
        var inputText by remember(selectedSubject) { mutableStateOf(selectedSubject) }

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
                        if (newName.isNotBlank() && newName != selectedSubject) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    QuizDatabase.getInstance(context)
                                        .questionDao()
                                        .updateSubject(selectedSubject, newName)
                                }
                                Toast.makeText(context, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                                selectedSubject = newName
                                loadData()
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = inputText.isNotBlank() && inputText.trim() != selectedSubject
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = {
                Text("确定要删除「${selectedSubject}」题库吗？\n" +
                        "此操作不可撤销，共 ${subjects.find { it.subject == selectedSubject }?.count ?: 0} 道题目将被删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                QuizDatabase.getInstance(context)
                                    .questionDao()
                                    .deleteBySubject(selectedSubject)
                            }
                            Toast.makeText(context, "已删除「${selectedSubject}」题库", Toast.LENGTH_SHORT).show()
                            selectedSubject = ""
                            showDeleteConfirm = false
                            loadData()
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
                    // 导入按钮
                    OutlinedButton(
                        onClick = { onImportClick?.invoke() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("导入", fontSize = 14.sp)
                    }

                    // 重命名按钮（选中时启用）
                    OutlinedButton(
                        onClick = {
                            renameText = selectedSubject
                            showRenameDialog = true
                        },
                        enabled = selectedSubject.isNotBlank(),
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

                    // 删除按钮（选中时启用）
                    Button(
                        onClick = { showDeleteConfirm = true },
                        enabled = selectedSubject.isNotBlank(),
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
                }
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF4CAF50))
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
            // 统计信息
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Text(
                    text = "共 ${subjects.size} 个题库，$totalCount 道题目",
                    fontSize = 13.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // 题库列表
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
                            isSelected = selectedSubject == item.subject,
                            onClick = {
                                selectedSubject = if (selectedSubject == item.subject) "" else item.subject
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
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：名称
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

            // 右侧：题目数量
            Text(
                text = "${count}道",
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF2196F3) else Color(0xFF999999),
                fontWeight = FontWeight.Medium
            )
        }
    }
}