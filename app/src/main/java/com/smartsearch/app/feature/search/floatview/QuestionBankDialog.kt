package com.smartsearch.app.feature.search.floatview

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.local.entity.QuestionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库管理对话框。
 *
 * 功能：
 * - 按学科分类查看题目列表
 * - 查看题目详情
 * - 删除单道题目
 * - 清空题库
 */
class QuestionBankDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private lateinit var contentLayout: LinearLayout

    companion object {
        private const val TAG = "QuestionBankDialog"
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    fun show() {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.WHITE)
        }

        // ── 标题行 ──
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val titleView = TextView(context).apply {
            text = "题库管理"
            textSize = 20f
            setTextColor(Color.parseColor("#333333"))
            paint.isFakeBoldText = true
        }
        val closeButton = Button(context).apply {
            text = "✕"
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        titleRow.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(closeButton)
        rootLayout.addView(titleRow)

        // ── 内容区域（可滚动） ──
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        // ── 底部按钮行 ──
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        val clearButton = Button(context).apply {
            text = "清空题库"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F44336"))
            setOnClickListener { onClearAll() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                WRAP
            )
        }
        buttonRow.addView(clearButton)
        rootLayout.addView(buttonRow)

        // 创建对话框
        dialog = AlertDialog.Builder(context)
            .setView(rootLayout)
            .setCancelable(true)
            .create()

        dialog?.window?.apply {
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.8).toInt()
            )
            setGravity(Gravity.CENTER)
        }

        dialog?.show()
        loadData()
    }

    /**
     * 加载题库数据并显示。
     */
    private fun loadData() {
        CoroutineScope(Dispatchers.Main).launch {
            val dao = QuizDatabase.getInstance(context).questionDao()
            val (totalCount, subjects) = withContext(Dispatchers.IO) {
                val count = dao.getCount()
                val subs = dao.getAllSubjects()
                Pair(count, subs)
            }

            contentLayout.removeAllViews()

            // ── 统计信息 ──
            val statsView = TextView(context).apply {
                text = "共 $totalCount 道题目"
                textSize = 14f
                setTextColor(Color.parseColor("#666666"))
                setPadding(0, 12, 0, 8)
            }
            contentLayout.addView(statsView)

            if (totalCount == 0) {
                val emptyView = TextView(context).apply {
                    text = "题库为空，请先导入题目"
                    textSize = 15f
                    setTextColor(Color.parseColor("#999999"))
                    gravity = Gravity.CENTER
                    setPadding(0, 60, 0, 60)
                }
                contentLayout.addView(emptyView)
                return@launch
            }

            // ── 按学科分组显示 ──
            if (subjects.isNotEmpty()) {
                subjects.forEach { subject ->
                    addSubjectSection(dao, subject)
                }
            }

            // ── 未分类题目 ──
            addSubjectSection(dao, "")
        }
    }

    /**
     * 添加一个学科分组。
     */
    private fun addSubjectSection(dao: com.smartsearch.app.data.local.dao.QuestionDao, subject: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val questions = withContext(Dispatchers.IO) {
                if (subject.isNotBlank()) {
                    dao.findBySubject(subject)
                } else {
                    // 未分类题目：学科为空或空白
                    dao.getAllQuestions().filter { it.subject.isBlank() }
                }
            }

            if (questions.isEmpty()) return@launch

            // ── 学科标题 ──
            val sectionTitle = TextView(context).apply {
                val label = if (subject.isNotBlank()) subject else "未分类"
                text = "【$label】(${questions.size}题)"
                textSize = 15f
                paint.isFakeBoldText = true
                setTextColor(Color.parseColor("#4CAF50"))
                setPadding(0, 16, 0, 4)
            }
            contentLayout.addView(sectionTitle)

            // ── 题目列表（最多显示前 50 条） ──
            questions.take(50).forEach { q ->
                addQuestionItem(q)
            }

            if (questions.size > 50) {
                val moreView = TextView(context).apply {
                    text = "... 还有 ${questions.size - 50} 题"
                    textSize = 13f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(0, 4, 0, 4)
                }
                contentLayout.addView(moreView)
            }
        }
    }

    /**
     * 添加一道题目的行。
     */
    private fun addQuestionItem(q: QuestionEntity) {
        val itemLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            setOnClickListener { showQuestionDetail(q) }
        }

        // 题目内容（截断显示）
        val questionText = TextView(context).apply {
            text = q.question.take(40) + if (q.question.length > 40) "..." else ""
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        itemLayout.addView(questionText)

        // 删除按钮
        val deleteButton = Button(context).apply {
            text = "删除"
            textSize = 12f
            setTextColor(Color.parseColor("#F44336"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                onDeleteQuestion(q)
            }
        }
        itemLayout.addView(deleteButton)

        // 分隔线
        contentLayout.addView(itemLayout)
        contentLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        })
    }

    /**
     * 显示题目详情。
     */
    private fun showQuestionDetail(q: QuestionEntity) {
        val detailDialog = AlertDialog.Builder(context)
        val sb = SpannableStringBuilder()
        sb.append("题目：${q.question}\n\n")
        if (q.options.isNotBlank()) {
            sb.append("选项：$q.options\n\n")
        }
        sb.append("答案：${q.answer}\n\n")
        if (q.explanation.isNotBlank()) {
            sb.append("解析：${q.explanation}\n\n")
        }
        if (q.subject.isNotBlank()) {
            sb.append("学科：${q.subject}\n")
        }
        sb.append("来源：${q.source}")

        detailDialog.setTitle("题目详情")
            .setMessage(sb)
            .setPositiveButton("关闭", null)
            .setNegativeButton("删除此题") { _: android.content.DialogInterface, _: Int -> onDeleteQuestion(q) }
            .show()
    }

    /**
     * 删除单道题目。
     */
    private fun onDeleteQuestion(q: QuestionEntity) {
        AlertDialog.Builder(context)
            .setTitle("确认删除")
            .setMessage("确定删除此题吗？\n\n${q.question.take(50)}")
            .setPositiveButton("删除") { _: DialogInterface, _: Int ->
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        QuizDatabase.getInstance(context).questionDao().delete(q)
                    }
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    loadData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清空全部题库。
     */
    private fun onClearAll() {
        AlertDialog.Builder(context)
            .setTitle("确认清空")
            .setMessage("确定要清空所有题目吗？此操作不可撤销！")
            .setPositiveButton("清空全部") { _: DialogInterface, _: Int ->
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        QuizDatabase.getInstance(context).questionDao().deleteAll()
                    }
                    Toast.makeText(context, "题库已清空", Toast.LENGTH_SHORT).show()
                    loadData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    }