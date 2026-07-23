package com.smartsearch.app.feature.search.floatview

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.local.entity.PracticeRecordEntity
import com.smartsearch.app.data.local.entity.QuestionEntity
import com.smartsearch.app.data.parser.AnswerCleaner
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练习模式对话框。
 *
 * 支持随机练习和顺序练习两种模式。
 * 用户选择答案后提交，可查看正确答案和解析。
 * 答错时自动归档到错题表，练习结束后自动保存练习记录。
 */
class PracticeDialog(private val context: Context) {

    companion object {
        private const val TAG = "PracticeDialog"
        private const val BATCH_SIZE = 20
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    // ==================== 状态 ====================

    /** 练习模式 */
    enum class PracticeMode {
        /** 随机抽取 */
        RANDOM,
        /** 顺序练习 */
        SEQUENTIAL
    }

    /** 当前练习模式 */
    private var currentMode = PracticeMode.RANDOM

    /** 所有题目列表 */
    private var allQuestions: List<QuestionEntity> = emptyList()

    /** 当前题目索引 */
    private var currentIndex = 0

    /** 选中的选项索引（-1 表示未选择） */
    private var selectedOptionIndex = -1

    /** 是否已提交答案 */
    private var isSubmitted = false

    /** 正确题目数 */
    private var correctCount = 0

    /** 已答题数 */
    private var answeredCount = 0

    /** 练习开始时间戳 */
    private var startTime = 0L

    // ==================== 视图引用 ====================

    private var dialog: AlertDialog? = null
    private lateinit var titleText: TextView
    private lateinit var progressText: TextView
    private lateinit var questionText: TextView
    private lateinit var optionsGroup: RadioGroup
    private lateinit var submitButton: Button
    private lateinit var nextButton: Button
    private lateinit var resultText: TextView
    private lateinit var modeToggle: Button
    private lateinit var scoreText: TextView

    /**
     * 显示练习对话框。
     */
    fun show() {
        // 构建布局
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.WHITE)
        }

        // ── 标题行：模式切换 + 关闭 ──
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        modeToggle = Button(context).apply {
            text = "随机模式"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setOnClickListener { toggleMode() }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(0, 0, 8, 0) }
        }
        val closeButton = Button(context).apply {
            text = "✕"
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        titleRow.addView(modeToggle)
        titleRow.addView(closeButton)
        rootLayout.addView(titleRow)

        // ── 进度和得分 ──
        val infoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        progressText = TextView(context).apply {
            text = "加载中..."
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
        }
        scoreText = TextView(context).apply {
            text = "正确: 0/0"
            textSize = 14f
            setTextColor(Color.parseColor("#4CAF50"))
            gravity = Gravity.END
        }
        infoRow.addView(progressText, LinearLayout.LayoutParams(0, WRAP, 1f))
        infoRow.addView(scoreText, LinearLayout.LayoutParams(WRAP, WRAP))
        rootLayout.addView(infoRow)

        // ── 分隔线 ──
        rootLayout.addView(createDivider())

        // ── 题目文本 ──
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        questionText = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(8f, 1f)
            movementMethod = ScrollingMovementMethod()
        }
        scrollView.addView(questionText)
        rootLayout.addView(scrollView)

        // ── 选项区域 ──
        optionsGroup = RadioGroup(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(optionsGroup)

        // ── 结果文本 ──
        resultText = TextView(context).apply {
            textSize = 15f
            visibility = View.GONE
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        }
        rootLayout.addView(resultText)

        // ── 按钮行 ──
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        submitButton = Button(context).apply {
            text = "提交答案"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setOnClickListener { onSubmit() }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(0, 0, 8, 0) }
        }
        nextButton = Button(context).apply {
            text = "下一题"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2196F3"))
            setOnClickListener { onNext() }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(8, 0, 0, 0) }
        }
        buttonRow.addView(submitButton)
        buttonRow.addView(nextButton)
        rootLayout.addView(buttonRow)

        // 创建对话框
        dialog = AlertDialog.Builder(context)
            .setView(rootLayout)
            .setCancelable(true)
            .create()

        dialog?.window?.apply {
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.75).toInt()
            )
            setGravity(Gravity.CENTER)
        }

        dialog?.show()

        // 记录开始时间
        startTime = System.currentTimeMillis()

        // 加载题目
        loadQuestions()
    }

    /**
     * 从数据库加载题目。
     */
    private fun loadQuestions() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dao = QuizDatabase.getInstance(context).questionDao()
                val all = withContext(Dispatchers.IO) {
                    dao.getAllQuestions()
                }
                allQuestions = if (currentMode == PracticeMode.RANDOM) {
                    all.shuffled()
                } else {
                    all.sortedBy { it.id }
                }

                if (allQuestions.isEmpty()) {
                    progressText.text = "题库为空，请先导入题目"
                    submitButton.isEnabled = false
                    return@launch
                }

                currentIndex = 0
                showQuestion()
            } catch (e: Exception) {
                Log.e("PracticeDialog", "加载题目异常: ${e.message}", e)
                progressText.text = "加载失败，请重试"
                submitButton.isEnabled = false
            }
        }
    }

    /**
     * 显示当前题目。
     */
    private fun showQuestion() {
        if (currentIndex >= allQuestions.size) {
            showComplete()
            return
        }

        val q = allQuestions[currentIndex]
        isSubmitted = false
        selectedOptionIndex = -1
        questionText.text = buildQuestionText(q)
        progressText.text = "第 ${currentIndex + 1}/${allQuestions.size} 题"
        resultText.visibility = View.GONE
        submitButton.visibility = View.VISIBLE
        submitButton.isEnabled = true
        submitButton.text = "提交答案"
        submitButton.setOnClickListener { onSubmit() }
        nextButton.visibility = View.GONE

        // 解析选项
        optionsGroup.removeAllViews()
        val options = parseOptions(q.options)
        if (options.isEmpty()) {
            // 没有选项 → 显示默认 ABCD 选项
            listOf("A", "B", "C", "D").forEach { label ->
                addOptionButton("$label. 请输入答案")
            }
        } else {
            options.forEach { option ->
                addOptionButton(option)
            }
        }
    }

    /**
     * 构建题目文本（含选项字母标签）。
     */
    private fun buildQuestionText(q: QuestionEntity): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("题目：${q.question}")
        if (q.subject.isNotBlank()) {
            sb.append("\n\n")
            val start = sb.length
            sb.append("【${q.subject}】")
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#4CAF50")),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    /**
     * 解析选项 JSON 字符串为列表。
     * 支持多种格式：JSON数组、换行分隔、前缀标记分隔、逗号分隔。
     */
    private fun parseOptions(options: String): List<String> {
        if (options.isBlank()) return emptyList()

        // 1. 尝试 JSON 数组解析
        try {
            val jsonArray = JsonParser.parseString(options).asJsonArray
            val result = jsonArray.map { it.asString }
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {
        }

        // 2. 尝试换行分隔
        val newlineSplit = options.split("\n").filter { it.isNotBlank() }
        if (newlineSplit.size >= 2) return newlineSplit

        // 3. 尝试按选项前缀标记拆分（A. B. C. D.）
        val splitByPrefix = options.split(Regex("""(?=[A-Da-d][.、．)）])""")).filter { it.isNotBlank() }
        if (splitByPrefix.size >= 2) return splitByPrefix

        // 4. 尝试逗号/分号分隔
        val commaSplit = options.split(Regex("[,;，；]")).filter { it.isNotBlank() }
        if (commaSplit.size >= 2) return commaSplit

        return listOf(options)
    }

    /**
     * 添加一个选项按钮到 RadioGroup。
     */
    private fun addOptionButton(text: String) {
        val radio = RadioButton(context).apply {
            this.text = text
            textSize = 15f
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                if (!isSubmitted) {
                    selectedOptionIndex = optionsGroup.indexOfChild(this)
                }
            }
        }
        optionsGroup.addView(radio)
    }

    /**
     * 提交答案。
     * 答错时自动归档到错题表。
     */
    private fun onSubmit() {
        if (selectedOptionIndex < 0) {
            resultText.text = "请选择一个答案"
            resultText.setTextColor(Color.parseColor("#FF5722"))
            resultText.visibility = View.VISIBLE
            return
        }

        val q = allQuestions[currentIndex]
        val options = parseOptions(q.options)
        val selectedAnswer = if (selectedOptionIndex < options.size) {
            options[selectedOptionIndex]
        } else {
            ""
        }

        isSubmitted = true
        submitButton.isEnabled = false
        submitButton.visibility = View.GONE
        nextButton.visibility = View.VISIBLE
        answeredCount++

        // 判断是否正确
        val isCorrect = AnswerCleaner.compare(selectedAnswer, q.answer)

        if (isCorrect) {
            correctCount++
        } else {
            // 答错时自动归档到错题表
            CoroutineScope(Dispatchers.Main).launch {
                withContext(Dispatchers.IO) {
                    QuizDatabase.getInstance(context).wrongQuestionDao()
                        .recordWrong(q.id, System.currentTimeMillis())
                }
            }
        }

        // 显示结果
        val sb = SpannableStringBuilder()
        if (isCorrect) {
            sb.append("✓ 回答正确！")
            resultText.setBackgroundColor(Color.parseColor("#E8F5E9"))
            resultText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            sb.append("✗ 回答错误")
            resultText.setBackgroundColor(Color.parseColor("#FFEBEE"))
            resultText.setTextColor(Color.parseColor("#C62828"))
        }
        sb.append("\n\n正确答案：${q.answer}")
        if (q.explanation.isNotBlank()) {
            sb.append("\n\n解析：${q.explanation}")
        }
        resultText.text = sb
        resultText.visibility = View.VISIBLE
        scoreText.text = "正确: $correctCount/$answeredCount"

        // 标记选中选项的正确/错误颜色
        for (i in 0 until optionsGroup.childCount) {
            val radio = optionsGroup.getChildAt(i) as? RadioButton ?: continue
            if (i < options.size) {
                val optText = options[i]
                if (AnswerCleaner.compare(optText, q.answer)) {
                    radio.setTextColor(Color.parseColor("#2E7D32")) // 绿色 = 正确答案
                } else if (i == selectedOptionIndex) {
                    radio.setTextColor(Color.parseColor("#C62828")) // 红色 = 选错
                }
            }
        }
    }

    /**
     * 下一题。
     */
    private fun onNext() {
        currentIndex++
        showQuestion()
    }

    /**
     * 切换练习模式（随机/顺序）。
     */
    private fun toggleMode() {
        currentMode = if (currentMode == PracticeMode.RANDOM) {
            PracticeMode.SEQUENTIAL
        } else {
            PracticeMode.RANDOM
        }
        modeToggle.text = if (currentMode == PracticeMode.RANDOM) "随机模式" else "顺序模式"
        currentIndex = 0
        correctCount = 0
        answeredCount = 0
        scoreText.text = "正确: 0/0"
        isSubmitted = false
        selectedOptionIndex = -1
        startTime = System.currentTimeMillis()

        // 重新加载题目
        loadQuestions()
    }

    /**
     * 完成所有题目。
     * 显示统计信息（正确数、正确率、耗时），自动保存练习记录。
     */
    private fun showComplete() {
        val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
        val accuracy = if (answeredCount > 0) correctCount.toFloat() / answeredCount else 0f

        // 自动保存练习记录
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                QuizDatabase.getInstance(context).practiceRecordDao().insert(
                    PracticeRecordEntity(
                        totalQuestions = answeredCount,
                        correctCount = correctCount,
                        accuracy = accuracy,
                        durationSeconds = durationSeconds,
                        practiceTime = System.currentTimeMillis()
                    )
                )
            }
        }

        progressText.text = "练习完成！"
        questionText.text = "恭喜你完成了所有 ${allQuestions.size} 道题目！"
        optionsGroup.removeAllViews()

        // 统计信息
        val durationText = if (durationSeconds >= 60) {
            "${durationSeconds / 60}分${durationSeconds % 60}秒"
        } else {
            "${durationSeconds}秒"
        }
        resultText.text = "最终成绩：$correctCount/$answeredCount 正确" +
                "\n正确率：${String.format("%.1f", accuracy * 100)}%" +
                "\n耗时：$durationText"
        resultText.visibility = View.VISIBLE
        submitButton.visibility = View.GONE

        // 重新开始按钮
        nextButton.apply {
            text = if (currentMode == PracticeMode.RANDOM) "再抽一轮" else "重新开始"
            visibility = View.VISIBLE
            setOnClickListener {
                currentIndex = 0
                correctCount = 0
                answeredCount = 0
                scoreText.text = "正确: 0/0"
                startTime = System.currentTimeMillis()
                loadQuestions()
            }
        }

        // 退出按钮
        submitButton.visibility = View.VISIBLE
        submitButton.text = "退出"
        submitButton.isEnabled = true
        submitButton.setOnClickListener { dismiss() }
    }

    /**
     * 创建分隔线。
     */
    private fun createDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 12, 0, 12) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
    }

    /**
     * 关闭对话框。
     */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}