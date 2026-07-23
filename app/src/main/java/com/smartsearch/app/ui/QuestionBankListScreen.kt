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
 * 题库选择页 —— 展示所有学科分类，用户选择后进入答题。
 *
 * 功能：
 * - 按学科分组展示题库列表，每条显示学科名和题目数量
 * - 单选选中，选中项高亮
 * - 底部选择练习模式（顺序/随机）
 * - 点击「开始练习」进入答题页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankListScreen(
    onBack: () -> Unit,
    onStartPractice: (subject: String, mode: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var subjects by remember { mutableStateOf<List<SubjectCountItem>>(emptyList()) }
    var selectedSubject by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("SEQUENTIAL") }
    var totalCount by remember { mutableIntStateOf(0) }
    var isCheckingQuestions by remember { mutableStateOf(false) }

    // 加载题库数据
    LaunchedEffect(Unit) {
        try {
            val db = QuizDatabase.getInstance(context)
            var loadedSubjects = emptyList<SubjectCountItem>()
            var allCount = 0
            var firstSubject = ""
            withContext(Dispatchers.IO) {
                try {
                    val allSubjects = db.questionDao().getAllSubjects()
                    loadedSubjects = allSubjects.map { subject ->
                        SubjectCountItem(subject, db.questionDao().getCountBySubject(subject))
                    }
                    allCount = db.questionDao().getCount()
                    firstSubject = if (loadedSubjects.isNotEmpty()) loadedSubjects.first().subject else ""
                } catch (e: Exception) {
                    Log.e("QuestionBankList", "加载题库数据异常: ${e.message}", e)
                }
            }
            // 状态修改必须在主线程
            subjects = loadedSubjects
            totalCount = allCount
            selectedSubject = firstSubject
        } catch (e: Exception) {
            Log.e("QuestionBankList", "初始化数据库异常: ${e.message}", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题库练习") },
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
            // 底部：模式选择 + 开始练习按钮
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 模式选择
                    Text(
                        text = "练习模式",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF666666)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModeChip(
                            text = "顺序练习",
                            isSelected = selectedMode == "SEQUENTIAL",
                            onClick = { selectedMode = "SEQUENTIAL" },
                            modifier = Modifier.weight(1f)
                        )
                        ModeChip(
                            text = "随机练习",
                            isSelected = selectedMode == "RANDOM",
                            onClick = { selectedMode = "RANDOM" },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 开始练习按钮
                    Button(
                        onClick = {
                            scope.launch {
                                isCheckingQuestions = true
                                Log.d("【PRACTICE_LOG】", "开始练习按钮点击: selectedSubject=$selectedSubject mode=$selectedMode")
                                try {
                                    val db = QuizDatabase.getInstance(context)
                                    Log.d("【PRACTICE_LOG】", "QuizDatabase 获取成功")
                                    val checkResult = withContext(Dispatchers.IO) {
                                        try {
                                            // ① 二次校验：用选中的题库ID查询数据库，校验题库是否已被删除
                                            val subjectToCheck = selectedSubject
                                            Log.d("【PRACTICE_LOG】", "IO线程: 二次校验 subjectToCheck=$subjectToCheck")
                                            if (subjectToCheck.isNotEmpty()) {
                                                val subjects = db.questionDao().getAllSubjects()
                                                Log.d("【PRACTICE_LOG】", "IO线程: getAllSubjects 结果=${subjects.size}条, 是否包含=${subjectToCheck in subjects}")
                                                if (subjectToCheck !in subjects) {
                                                    // 题库已被删除
                                                    Log.d("【PRACTICE_LOG】", "题库已被删除!")
                                                    return@withContext Pair(true, 0)
                                                }
                                            }
                                            // ② 查询题目数量
                                            val count = if (selectedSubject.isEmpty()) {
                                                db.questionDao().getCount()
                                            } else {
                                                db.questionDao().getCountBySubject(selectedSubject)
                                            }
                                            Log.d("【PRACTICE_LOG】", "IO线程: 题目数量查询结果=$count")
                                            Pair(false, count)
                                        } catch (e: Exception) {
                                            Log.e("【PRACTICE_LOG】", "题库查询异常: ${e.message}", e)
                                            Pair(false, -1)
                                        }
                                    }
                                    Log.d("【PRACTICE_LOG】", "校验结果: deleted=${checkResult.first} count=${checkResult.second}")
                                    if (checkResult.first) {
                                        Log.d("【PRACTICE_LOG】", "题库已删除, 拦截跳转")
                                        Toast.makeText(
                                            context,
                                            "该题库已被删除，请重新选择",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        // 刷新列表（全在 IO 线程执行）
                                        withContext(Dispatchers.IO) {
                                            try {
                                                Log.d("【PRACTICE_LOG】", "刷新列表")
                                                val allSubjects = db.questionDao().getAllSubjects()
                                                val loadedSubjects = allSubjects.map { subject ->
                                                    SubjectCountItem(subject, db.questionDao().getCountBySubject(subject))
                                                }
                                                // 回到主线程更新 UI 状态
                                                withContext(Dispatchers.Main) {
                                                    subjects = loadedSubjects
                                                    selectedSubject = if (loadedSubjects.isNotEmpty()) loadedSubjects.first().subject else ""
                                                    Log.d("【PRACTICE_LOG】", "列表刷新完成: ${loadedSubjects.size}条")
                                                }
                                            } catch (_: Exception) { }
                                        }
                                    } else if (checkResult.second == 0) {
                                        // 空列表拦截
                                        Log.d("【PRACTICE_LOG】", "题目列表为空, 拦截跳转")
                                        Toast.makeText(
                                            context,
                                            if (selectedSubject.isEmpty()) "题库中暂无题目，请先导入" else "该学科暂无题目，请先导入",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (checkResult.second < 0) {
                                        Log.d("【PRACTICE_LOG】", "查询异常 count<0")
                                        Toast.makeText(
                                            context,
                                            "查询题目数量异常，请重试",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        // count > 0，跳转答题页
                                        Log.d("【PRACTICE_LOG】", "校验通过, 跳转答题页: subject=$selectedSubject mode=$selectedMode")
                                        if (selectedSubject.isNotBlank()) {
                                            onStartPractice(selectedSubject, selectedMode)
                                        } else {
                                            // 空字符串兜底：跳转全部题库
                                            Log.d("【PRACTICE_LOG】", "subject为空, 兜底为全部题库")
                                            onStartPractice("", selectedMode)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("【PRACTICE_LOG】", "开始练习异常: ${e.message}", e)
                                    Toast.makeText(
                                        context,
                                        "数据库访问异常，请重试",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isCheckingQuestions = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = selectedSubject.isNotBlank() && !isCheckingQuestions,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isCheckingQuestions) {
                            Text(
                                text = "检查中...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "开始练习",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (subjects.isEmpty()) {
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
                        text = "请先在首页导入题目",
                        fontSize = 14.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        } else {
            // 题库列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 全部题库选项
                item {
                    SubjectCard(
                        subject = "全部题库",
                        count = totalCount,
                        isSelected = selectedSubject.isEmpty(),
                        onClick = { selectedSubject = "" }
                    )
                }
                // 各学科分类
                items(subjects, key = { it.subject }) { item ->
                    SubjectCard(
                        subject = item.subject,
                        count = item.count,
                        isSelected = selectedSubject == item.subject,
                        onClick = { selectedSubject = item.subject }
                    )
                }
                // 底部留白
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/**
 * 学科题库卡片。
 */
@Composable
private fun SubjectCard(
    subject: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFFE8F5E9) else Color.White
    val borderColor = if (isSelected) Color(0xFF4CAF50) else Color(0xFFE0E0E0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isSelected) 1.5.dp else 0.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subject,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color(0xFF2E7D32) else Color(0xFF333333)
            )
            Text(
                text = "${count}题",
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF4CAF50) else Color(0xFF999999),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 模式选择标签。
 */
@Composable
private fun ModeChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Color(0xFF4CAF50) else Color(0xFFF5F5F5)
    val textColor = if (isSelected) Color.White else Color(0xFF666666)

    Box(
        modifier = modifier
            .height(42.dp)
            .clickable(onClick = onClick)
            .background(bgColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}

/**
 * 学科题目数量数据类。
 */
private data class SubjectCountItem(
    val subject: String,
    val count: Int
)