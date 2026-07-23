package com.smartsearch.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Refresh
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.local.entity.PracticeRecordEntity
import com.smartsearch.app.data.local.entity.PracticeSessionEntity
import com.smartsearch.app.data.local.entity.QuestionEntity
import com.smartsearch.app.data.parser.AnswerCleaner
import com.smartsearch.app.data.parser.ExcelImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 答题页面 —— 核心练习答题交互。
 *
 * 功能：
 * - 顶部：标题 + 重置进度按钮
 * - 进度条：显示当前作答进度
 * - 题目区域：题型、题干、选项（单选）、收藏按钮
 * - 操作按钮：提交答案、上一题、下一题
 * - 进度持久化：支持中途退出后恢复
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerScreen(
    subject: String,
    mode: String,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    Log.d("【PRACTICE_LOG】", "AnswerScreen 入口: subject=$subject mode=$mode")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    Log.d("【PRACTICE_LOG】", "状态初始化前")

    // ── 核心状态 ──
    var questions by remember { mutableStateOf<List<QuestionEntity>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) } // questionId -> selectedOptionIndex
    var bookmarks by remember { mutableStateOf<Set<Long>>(emptySet()) } // questionId set
    var correctCount by remember { mutableIntStateOf(0) }
    var answeredCount by remember { mutableIntStateOf(0) }
    var submittedQuestions by remember { mutableStateOf<Set<Long>>(emptySet()) } // 已提交的 questionId
    var sessionId by remember { mutableLongStateOf(0L) }
    var startTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isLoading by remember { mutableStateOf(true) }
    var showResetConfirm by remember { mutableStateOf(false) }
    Log.d("【PRACTICE_LOG】", "状态初始化完成, isLoading=$isLoading")

    // ── 加载题目 ──
    LaunchedEffect(subject, mode) {
        Log.d("【PRACTICE_LOG】", "LaunchedEffect 开始: subject=$subject mode=$mode")
        // Bundle参数非空兜底：subject 和 mode 为空时使用默认值
        val safeSubject = subject.ifBlank { "" }
        val safeMode = mode.ifBlank { "SEQUENTIAL" }
        try {
            Log.d("【PRACTICE_LOG】", "开始获取数据库实例")
            val db = QuizDatabase.getInstance(context)
            Log.d("【PRACTICE_LOG】", "数据库实例获取成功")

            // 清理之前导入时可能误导入的表头行脏数据
            try {
                ExcelImporter.cleanupHeaderRows(context)
            } catch (e: Exception) {
                Log.e("【PRACTICE_LOG】", "清理表头行脏数据异常: ${e.message}", e)
            }
            // 在 IO 线程执行数据库操作，返回结果
            val result = withContext(Dispatchers.IO) {
                try {
                    Log.d("【PRACTICE_LOG】", "IO线程: 开始查询练习会话")
                    // 检查是否有未完成的练习会话
                    val existingSession = try {
                        db.practiceSessionDao().getLatestIncompleteSession()
                    } catch (e: Exception) {
                        Log.e("【PRACTICE_LOG】", "查询练习会话异常: ${e.message}", e)
                        null
                    }
                    Log.d("【PRACTICE_LOG】", "IO线程: 查询会话完成: existingSession=${existingSession != null}")

                    if (existingSession != null &&
                        existingSession.subject == safeSubject &&
                        existingSession.mode == safeMode
                    ) {
                        // 恢复进度
                        val ids: List<Long> = try {
                            gson.fromJson(
                                existingSession.questionIds,
                                object : TypeToken<List<Long>>() {}.type
                            )
                        } catch (e: Exception) {
                            Log.e("【PRACTICE_LOG】", "解析题目ID列表异常: ${e.message}", e)
                            emptyList()
                        }
                        val loadedQuestions = ids.mapNotNull { id -> db.questionDao().findById(id) }
                        val savedAnswers: Map<Long, Int> = try {
                            gson.fromJson(
                                existingSession.answers,
                                object : TypeToken<Map<Long, Int>>() {}.type
                            ) ?: emptyMap()
                        } catch (e: Exception) {
                            Log.e("【PRACTICE_LOG】", "解析答案数据异常: ${e.message}", e)
                            emptyMap()
                        }
                        val savedBookmarks: List<Long> = try {
                            gson.fromJson(
                                existingSession.bookmarks,
                                object : TypeToken<List<Long>>() {}.type
                            ) ?: emptyList()
                        } catch (e: Exception) {
                            Log.e("【PRACTICE_LOG】", "解析收藏数据异常: ${e.message}", e)
                            emptyList()
                        }

                        LoadResult(
                            questions = loadedQuestions,
                            currentIndex = existingSession.currentIndex,
                            answers = savedAnswers,
                            bookmarks = savedBookmarks.toSet(),
                            correctCount = existingSession.correctCount,
                            answeredCount = existingSession.answeredCount,
                            sessionId = existingSession.id,
                            startTime = existingSession.startTime,
                            submittedQuestions = savedAnswers.keys,
                            isRestored = true
                        )
                    } else {
                        // 创建新会话
                        Log.d("【PRACTICE_LOG】", "IO线程: 创建新会话, safeSubject=$safeSubject safeMode=$safeMode")
                        val allQuestions = if (safeSubject.isBlank()) {
                            db.questionDao().getAllQuestions()
                        } else {
                            db.questionDao().findBySubject(safeSubject)
                        }
                        Log.d("【PRACTICE_LOG】", "IO线程: 查询题目完成, count=${allQuestions.size}")
                        // 随机排序空列表校验：空列表不调用 shuffled()
                        val orderedQuestions = if (allQuestions.isEmpty()) {
                            emptyList()
                        } else if (safeMode == "RANDOM") {
                            allQuestions.shuffled()
                        } else {
                            allQuestions.sortedBy { it.id }
                        }
                        var newSessionId = 0L
                        if (orderedQuestions.isNotEmpty()) {
                            val ids = orderedQuestions.map { it.id }
                            val newSession = PracticeSessionEntity(
                                subject = safeSubject,
                                mode = safeMode,
                                questionIds = gson.toJson(ids),
                                currentIndex = 0,
                                startTime = System.currentTimeMillis()
                            )
                            newSessionId = db.practiceSessionDao().insert(newSession)
                            Log.d("【PRACTICE_LOG】", "IO线程: 新会话已创建, sessionId=$newSessionId")
                        }
                        LoadResult(
                            questions = orderedQuestions,
                            currentIndex = 0,
                            answers = emptyMap(),
                            bookmarks = emptySet(),
                            correctCount = 0,
                            answeredCount = 0,
                            sessionId = newSessionId,
                            startTime = System.currentTimeMillis(),
                            submittedQuestions = emptySet(),
                            isRestored = false
                        )
                    }
                } catch (e: Exception) {
                    Log.e("【PRACTICE_LOG】", "加载题目异常: ${e.message}", e)
                    null
                }
            }
            Log.d("【PRACTICE_LOG】", "withContext 返回, result=${result != null}")
            // 状态修改必须在主线程
            result?.let { r ->
                Log.d("【PRACTICE_LOG】", "主线程: 开始更新状态, questions.size=${r.questions.size}")
                questions = r.questions
                currentIndex = r.currentIndex
                answers = r.answers
                bookmarks = r.bookmarks
                correctCount = r.correctCount
                answeredCount = r.answeredCount
                sessionId = r.sessionId
                startTime = r.startTime
                submittedQuestions = r.submittedQuestions
                Log.d("【PRACTICE_LOG】", "主线程: 状态更新完成, questions.size=${questions.size}")
            }
        } catch (e: Exception) {
            Log.e("【PRACTICE_LOG】", "获取数据库实例异常: ${e.message}", e)
        }
        isLoading = false
        Log.d("【PRACTICE_LOG】", "LaunchedEffect 结束, isLoading=false questions.size=${questions.size}")
    }

    // ── 保存进度辅助函数 ──
    fun saveProgress() {
        if (sessionId == 0L) return
        scope.launch(Dispatchers.IO) {
            val db = QuizDatabase.getInstance(context)
            val session = db.practiceSessionDao().getById(sessionId) ?: return@launch
            db.practiceSessionDao().update(
                session.copy(
                    currentIndex = currentIndex,
                    answers = gson.toJson(answers),
                    bookmarks = gson.toJson(bookmarks.toList()),
                    correctCount = correctCount,
                    answeredCount = answeredCount
                )
            )
        }
    }

    // ── 重置确认对话框 ──
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置进度") },
            text = { Text("确定要重置答题进度吗？所有答题记录将被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    scope.launch(Dispatchers.IO) {
                        val db = QuizDatabase.getInstance(context)
                        if (sessionId != 0L) {
                            db.practiceSessionDao().delete(
                                db.practiceSessionDao().getById(sessionId) ?: return@launch
                            )
                        }
                        // 重新加载题目
                        withContext(Dispatchers.Main) {
                            isLoading = true
                            currentIndex = 0
                            answers = emptyMap()
                            bookmarks = emptySet()
                            correctCount = 0
                            answeredCount = 0
                            submittedQuestions = emptySet()
                            sessionId = 0L
                            startTime = System.currentTimeMillis()
                            // 重新创建会话
                            val orderedQuestions = if (mode == "RANDOM") {
                                questions.shuffled()
                            } else {
                                questions.sortedBy { it.id }
                            }
                            questions = orderedQuestions
                            if (orderedQuestions.isNotEmpty()) {
                                val ids = orderedQuestions.map { it.id }
                                val newSession = PracticeSessionEntity(
                                    subject = subject,
                                    mode = mode,
                                    questionIds = gson.toJson(ids),
                                    currentIndex = 0,
                                    startTime = System.currentTimeMillis()
                                )
                                scope.launch(Dispatchers.IO) {
                                    sessionId = db.practiceSessionDao().insert(newSession)
                                }
                            }
                            isLoading = false
                        }
                    }
                }) {
                    Text("确定重置", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 主界面 ──
    Log.d("【PRACTICE_LOG】", "Scaffold 渲染前: isLoading=$isLoading questions.size=${questions.size} showResetConfirm=$showResetConfirm")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("答题") },
                navigationIcon = {
                    IconButton(onClick = {
                        saveProgress()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重置进度",
                            tint = Color.White
                        )
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
                AnswerBottomBar(
                    currentIndex = currentIndex,
                    totalCount = questions.size,
                    isSubmitted = currentIndex < questions.size &&
                            submittedQuestions.contains(questions[currentIndex].id),
                    hasPrev = currentIndex > 0,
                    hasNext = currentIndex < questions.size - 1,
                    onPrev = {
                        saveProgress()
                        if (currentIndex > 0) currentIndex--
                    },
                    onNext = {
                        saveProgress()
                        if (currentIndex < questions.size - 1) currentIndex++
                    },
                    onSubmit = {
                        val q = questions.getOrNull(currentIndex) ?: return@AnswerBottomBar
                        val selectedIndex = answers[q.id]
                        if (selectedIndex == null) return@AnswerBottomBar

                        // 检查答案（判断题型适配）
                        val isTrueFalse = isTrueFalseQuestion(q)
                        val selectedAnswer = if (isTrueFalse) {
                            // 判断题：index 0 → 正确, index 1 → 错误
                            if (selectedIndex == 0) "正确" else "错误"
                        } else {
                            val options = parseOptions(q.options)
                            if (selectedIndex < options.size) options[selectedIndex] else ""
                        }
                        Log.d("【PRACTICE_LOG】", "提交答案: questionId=${q.id} isTrueFalse=$isTrueFalse selectedIndex=$selectedIndex selectedAnswer=$selectedAnswer correctAnswer=${q.answer}")

                        val isCorrect = AnswerCleaner.compare(selectedAnswer, q.answer)

                        submittedQuestions = submittedQuestions + q.id
                        answeredCount++
                        if (isCorrect) correctCount++

                        // 答错自动归档错题
                        if (!isCorrect) {
                            scope.launch(Dispatchers.IO) {
                                QuizDatabase.getInstance(context)
                                    .wrongQuestionDao()
                                    .recordWrong(q.id, System.currentTimeMillis())
                            }
                        }

                        saveProgress()
                    }
                )
            }
        }
    ) { padding ->
        Log.d("【PRACTICE_LOG】", "Scaffold content 分支判断: isLoading=$isLoading")
        if (isLoading) {
            Log.d("【PRACTICE_LOG】", "isLoading分支: 开始渲染Box")
            // 先尝试渲染一个最简单的纯色背景Box，跳过CircularProgressIndicator
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Log.d("【PRACTICE_LOG】", "isLoading分支: Box内部开始渲染子组件")
                // 用Text替代CircularProgressIndicator测试
                Text(
                    text = "加载中...",
                    color = Color(0xFF4CAF50),
                    fontSize = 16.sp
                )
            }
            Log.d("【PRACTICE_LOG】", "isLoading分支: Box渲染完成")
        } else if (questions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无题目", fontSize = 16.sp, color = Color.Gray)
            }
        } else if (currentIndex >= questions.size) {
            // 已完成所有题目
            CompleteScreen(
                correctCount = correctCount,
                answeredCount = answeredCount,
                totalCount = questions.size,
                startTime = startTime,
                onFinish = onFinish,
                onRestart = {
                    scope.launch(Dispatchers.IO) {
                        val db = QuizDatabase.getInstance(context)
                        if (sessionId != 0L) {
                            db.practiceSessionDao().delete(
                                db.practiceSessionDao().getById(sessionId) ?: return@launch
                            )
                        }
                        // 保存练习记录
                        val duration = (System.currentTimeMillis() - startTime) / 1000
                        val accuracy = if (answeredCount > 0) correctCount.toFloat() / answeredCount else 0f
                        db.practiceRecordDao().insert(
                            PracticeRecordEntity(
                                subject = subject,
                                totalQuestions = answeredCount,
                                correctCount = correctCount,
                                accuracy = accuracy,
                                durationSeconds = duration,
                                practiceTime = System.currentTimeMillis()
                            )
                        )
                    }
                    // 重新开始
                    isLoading = true
                    currentIndex = 0
                    answers = emptyMap()
                    bookmarks = emptySet()
                    correctCount = 0
                    answeredCount = 0
                    submittedQuestions = emptySet()
                    sessionId = 0L
                    startTime = System.currentTimeMillis()
                    // 重新加载
                    scope.launch(Dispatchers.IO) {
                        val db = QuizDatabase.getInstance(context)
                        val allQuestions = if (subject.isBlank()) {
                            db.questionDao().getAllQuestions()
                        } else {
                            db.questionDao().findBySubject(subject)
                        }
                        val orderedQuestions = if (mode == "RANDOM") {
                            allQuestions.shuffled()
                        } else {
                            allQuestions.sortedBy { it.id }
                        }
                        questions = orderedQuestions
                        if (orderedQuestions.isNotEmpty()) {
                            val ids = orderedQuestions.map { it.id }
                            val newSession = PracticeSessionEntity(
                                subject = subject,
                                mode = mode,
                                questionIds = gson.toJson(ids),
                                currentIndex = 0,
                                startTime = System.currentTimeMillis()
                            )
                            sessionId = db.practiceSessionDao().insert(newSession)
                        }
                        isLoading = false
                    }
                }
            )
        } else {
            val q = questions[currentIndex]
            val isSubmitted = submittedQuestions.contains(q.id)
            val selectedIndex = answers[q.id]
            val isBookmarked = bookmarks.contains(q.id)

            // 进度条
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 进度条
                val progressValue = (currentIndex + 1).toFloat() / questions.size
                LinearProgressIndicator(
                    progress = progressValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )

                // 进度文本
                Text(
                    text = "第 ${currentIndex + 1}/${questions.size} 题",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center
                )

                // 题目内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // 题型标签 + 收藏按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 题型标签
                        Text(
                            text = if (q.subject.isNotBlank()) "【${q.subject}】" else "【选择题】",
                            fontSize = 13.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(
                                    Color(0xFFE8F5E9),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        // 收藏按钮
                        IconButton(
                            onClick = {
                                bookmarks = if (isBookmarked) {
                                    bookmarks - q.id
                                } else {
                                    bookmarks + q.id
                                }
                                saveProgress()
                            }
                        ) {
                            Icon(
                                imageVector = if (isBookmarked)
                                    Icons.Default.Bookmark
                                else
                                    Icons.Default.BookmarkBorder,
                                contentDescription = if (isBookmarked) "取消收藏" else "收藏",
                                tint = if (isBookmarked) Color(0xFFFF9800) else Color(0xFF999999)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 题干
                    Text(
                        text = q.question,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF333333),
                        lineHeight = 24.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    // 判断题目类型
                    val isTrueFalse = isTrueFalseQuestion(q)
                    Log.d("【PRACTICE_LOG】", "题型识别: questionId=${q.id} isTrueFalse=$isTrueFalse options原文=[${q.options.take(100)}] 答案=[${q.answer.take(50)}]")

                    if (isTrueFalse) {
                        // ── 判断题分支：渲染【正确】【错误】两个固定作答按钮 ──
                        Log.d("【PRACTICE_LOG】", "判断题渲染: 初始化正确/错误按钮")
                        listOf("正确" to 0, "错误" to 1).forEach { (label, idx) ->
                            val isSelected = selectedIndex == idx
                            OptionItem(
                                text = label,
                                index = idx,
                                isSelected = isSelected,
                                isSubmitted = isSubmitted,
                                isCorrect = isSubmitted && AnswerCleaner.compare(label, q.answer),
                                isWrong = isSubmitted && isSelected && !AnswerCleaner.compare(label, q.answer),
                                enabled = !isSubmitted,
                                onClick = {
                                    if (!isSubmitted) {
                                        answers = answers + (q.id to idx)
                                        Log.d("【PRACTICE_LOG】", "判断题选择: questionId=${q.id} selected=$label idx=$idx")
                                        saveProgress()
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    } else {
                        // ── 选择题分支：渲染选项列表 ──
                        val options = parseOptions(q.options)
                        Log.d("【PRACTICE_LOG】", "选择题渲染: questionId=${q.id} 选项数组长度=${options.size} 全部选项=${options}")
                        if (options.isEmpty()) {
                            Log.w("【PRACTICE_LOG】", "选择题选项为空: questionId=${q.id} 原始options=[${q.options}]")
                        }
                        options.forEachIndexed { index, option ->
                            Log.d("【PRACTICE_LOG】", "  选项[$index]: ${option.take(100)}")
                            val isSelected = selectedIndex == index
                            OptionItem(
                                text = option,
                                index = index,
                                isSelected = isSelected,
                                isSubmitted = isSubmitted,
                                isCorrect = isSubmitted && AnswerCleaner.compare(option, q.answer),
                                isWrong = isSubmitted && isSelected && !AnswerCleaner.compare(option, q.answer),
                                enabled = !isSubmitted,
                                onClick = {
                                    if (!isSubmitted) {
                                        answers = answers + (q.id to index)
                                        saveProgress()
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // 提交后显示结果
                    if (isSubmitted) {
                        Spacer(Modifier.height(16.dp))
                        val isCorrect = if (isTrueFalse) {
                            val label = if (selectedIndex == 0) "正确" else "错误"
                            AnswerCleaner.compare(label, q.answer)
                        } else {
                            val options = parseOptions(q.options)
                            options.getOrNull(selectedIndex ?: -1)?.let { selected ->
                                AnswerCleaner.compare(selected, q.answer)
                            } ?: false
                        }
                        Log.d("【PRACTICE_LOG】", "结果判定: questionId=${q.id} isTrueFalse=$isTrueFalse selectedIndex=$selectedIndex isCorrect=$isCorrect")

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCorrect) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (isCorrect) "✓ 回答正确！" else "✗ 回答错误",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCorrect) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "正确答案：${q.answer}",
                                    fontSize = 14.sp,
                                    color = Color(0xFF333333)
                                )
                                if (q.explanation.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "解析：${q.explanation}",
                                        fontSize = 14.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 统计信息
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "正确: $correctCount/$answeredCount",
                            fontSize = 14.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 题目加载结果（用于线程安全地传递数据）。
 */
private data class LoadResult(
    val questions: List<QuestionEntity>,
    val currentIndex: Int,
    val answers: Map<Long, Int>,
    val bookmarks: Set<Long>,
    val correctCount: Int,
    val answeredCount: Int,
    val sessionId: Long,
    val startTime: Long,
    val submittedQuestions: Set<Long>,
    val isRestored: Boolean
)

/**
 * 选项条目组件。
 */
@Composable
private fun OptionItem(
    text: String,
    index: Int,
    isSelected: Boolean,
    isSubmitted: Boolean,
    isCorrect: Boolean,
    isWrong: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val label = ('A' + index).toString()
    val bgColor = when {
        isSubmitted && isCorrect -> Color(0xFFE8F5E9)
        isSubmitted && isWrong -> Color(0xFFFFEBEE)
        isSelected -> Color(0xFFE3F2FD)
        else -> Color.White
    }
    val borderColor = when {
        isSubmitted && isCorrect -> Color(0xFF4CAF50)
        isSubmitted && isWrong -> Color(0xFFE53935)
        isSelected -> Color(0xFF2196F3)
        else -> Color(0xFFE0E0E0)
    }
    val textColor = when {
        isSubmitted && isCorrect -> Color(0xFF2E7D32)
        isSubmitted && isWrong -> Color(0xFFC62828)
        else -> Color(0xFF333333)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选项字母
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        when {
                            isSubmitted && isCorrect -> Color(0xFF4CAF50)
                            isSubmitted && isWrong -> Color(0xFFE53935)
                            isSelected -> Color(0xFF2196F3)
                            else -> Color(0xFFF5F5F5)
                        },
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected || isSubmitted) Color.White else Color(0xFF666666)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 选项文本
            Text(
                text = text,
                fontSize = 15.sp,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 底部操作栏。
 */
@Composable
private fun AnswerBottomBar(
    currentIndex: Int,
    totalCount: Int,
    isSubmitted: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 上一题
        OutlinedButton(
            onClick = onPrev,
            enabled = hasPrev,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("上一题", fontSize = 14.sp)
        }

        // 提交答案
        Button(
            onClick = onSubmit,
            enabled = !isSubmitted,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isSubmitted) "已提交" else "提交答案",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 下一题
        OutlinedButton(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("下一题", fontSize = 14.sp)
        }
    }
}

/**
 * 练习完成界面。
 */
@Composable
private fun CompleteScreen(
    correctCount: Int,
    answeredCount: Int,
    totalCount: Int,
    startTime: Long,
    onFinish: () -> Unit,
    onRestart: () -> Unit
) {
    val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
    val accuracy = if (answeredCount > 0) correctCount.toFloat() / answeredCount else 0f
    val durationText = if (durationSeconds >= 60) {
        "${durationSeconds / 60}分${durationSeconds % 60}秒"
    } else {
        "${durationSeconds}秒"
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🎉 练习完成！",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )

                Spacer(Modifier.height(24.dp))

                // 统计信息
                StatItem("总题数", "$totalCount 题")
                Spacer(Modifier.height(8.dp))
                StatItem("已答题", "$answeredCount 题")
                Spacer(Modifier.height(8.dp))
                StatItem("正确数", "$correctCount 题")
                Spacer(Modifier.height(8.dp))
                StatItem("正确率", "${String.format("%.1f", accuracy * 100)}%")
                Spacer(Modifier.height(8.dp))
                StatItem("耗时", durationText)

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onRestart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("再来一次", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("返回题库列表", fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * 统计项行。
 */
@Composable
private fun StatItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = Color(0xFF666666)
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333)
        )
    }
}

/**
 * 解析选项 JSON 字符串为列表。
 * 支持多种格式：
 * 1. JSON 数组：["A.xxx","B.yyy","C.zzz","D.www"]
 * 2. 换行分隔：A.xxx\nB.yyy\nC.zzz\nD.www
 * 3. 逗号分隔：A.xxx,B.yyy,C.zzz,D.www
 * 4. 空格分隔（兜底，仅当其他方式均失败时）
 */
private fun parseOptions(options: String): List<String> {
    if (options.isBlank()) {
        Log.d("【PRACTICE_LOG】", "parseOptions: options 为空字符串")
        return emptyList()
    }

    Log.d("【PRACTICE_LOG】", "parseOptions 原始: len=${options.length} content=[${options.take(200)}]")

    // 1. 尝试 JSON 数组解析
    try {
        val jsonArray = com.google.gson.JsonParser.parseString(options).asJsonArray
        val result = jsonArray.map { it.asString }
        Log.d("【PRACTICE_LOG】", "parseOptions JSON解析成功: count=${result.size} items=${result}")
        if (result.isNotEmpty()) return result
    } catch (_: Exception) {
        Log.d("【PRACTICE_LOG】", "parseOptions JSON解析失败，尝试其他格式")
    }

    // 2. 尝试换行分隔
    val newlineSplit = options.split("\n").filter { it.isNotBlank() }
    if (newlineSplit.size >= 2) {
        Log.d("【PRACTICE_LOG】", "parseOptions 换行分隔: count=${newlineSplit.size} items=${newlineSplit}")
        return newlineSplit
    }

    // 3. 尝试按常见选项分隔符拆分（如 "A. " 或 "A、" 或 "A." 前缀标记）
    val optionPrefixPattern = Regex("""(?:^|\n|[,;，；])\s*([A-Da-d][.、．)）])""")
    val prefixMatch = optionPrefixPattern.findAll(options).map { it.value }.toList()
    if (prefixMatch.size >= 2) {
        // 按选项前缀拆分
        val splitByPrefix = options.split(Regex("""(?=[A-Da-d][.、．)）])""")).filter { it.isNotBlank() }
        Log.d("【PRACTICE_LOG】", "parseOptions 前缀分隔: count=${splitByPrefix.size} items=${splitByPrefix}")
        if (splitByPrefix.size >= 2) return splitByPrefix
    }

    // 4. 尝试逗号/分号分隔
    val commaSplit = options.split(Regex("[,;，；]")).filter { it.isNotBlank() }
    if (commaSplit.size >= 2) {
        Log.d("【PRACTICE_LOG】", "parseOptions 逗号分隔: count=${commaSplit.size} items=${commaSplit}")
        return commaSplit
    }

    // 5. 兜底：按空格分隔（仅当选项短且无更好方式时）
    val spaceSplit = options.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (spaceSplit.size >= 2) {
        Log.d("【PRACTICE_LOG】", "parseOptions 空格分隔: count=${spaceSplit.size} items=${spaceSplit}")
        return spaceSplit
    }

    // 6. 最后兜底：返回单个选项
    Log.d("【PRACTICE_LOG】", "parseOptions 无法拆分，返回单元素列表: [${options}]")
    return listOf(options)
}

/**
 * 判断题目是否为判断题。
 *
 * 规则：如果 options 字段为空或空白，且 answer 字段不为空，则判定为判断题。
 * 判断题没有选项列表，直接渲染【正确】【错误】两个固定作答按钮。
 */
private fun isTrueFalseQuestion(q: QuestionEntity): Boolean {
    val isTF = q.options.isBlank() && q.answer.isNotBlank()
    Log.d("【PRACTICE_LOG】", "isTrueFalseQuestion: questionId=${q.id} optionsBlank=${q.options.isBlank()} answerNotBlank=${q.answer.isNotBlank()} result=$isTF")
    return isTF
}