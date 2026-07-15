package com.smartsearch.app.feature.search.capture

import android.content.Context
import android.util.Log
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.local.entity.QuestionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 题库检索器 —— 无障碍服务与录屏服务共用同一套题库检索逻辑。
 *
 * # 职责
 * 接收来自任意来源的题干文本（无障碍提取 / OCR 识别），
 * 在本地 Room 数据库中检索匹配的答案。
 *
 * # 检索策略（三级降级）
 * 1. 精确匹配：题干完整匹配 `questions.question`
 * 2. 模糊匹配：提取题干关键词（前 20 个字符），LIKE 模糊搜索
 * 3. 兜底：返回"未找到答案"提示
 *
 * # 使用方式
 * ```kotlin
 * // 无障碍服务中
 * val answer = QuestionBankSearcher.search(context, questionText)
 *
 * // 录屏服务中
 * val answer = QuestionBankSearcher.search(context, ocrText)
 * ```
 */
object QuestionBankSearcher {

    private const val TAG = "QuestionBankSearcher"

    /** 模糊匹配取前 N 个字符作为关键词 */
    private const val KEYWORD_LENGTH = 20

    /**
     * 根据题干在本地题库中搜索答案。
     *
     * 由于无障碍服务和录屏服务的回调在非协程线程，这里使用 [runBlocking]
     * 切换到 IO 线程查询 Room 数据库，避免阻塞主线程。
     *
     * @param context 上下文
     * @param question 题干文本
     * @return 答案文本，未找到时返回提示信息
     */
    fun search(context: Context, question: String): String {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            return "题干为空，无法检索"
        }

        return try {
            val db = QuizDatabase.getInstance(context)
            val dao = db.questionDao()

            // 使用 runBlocking 在当前线程执行协程查询
            // 调用方（无障碍服务/录屏服务）已在后台线程，不会阻塞主线程
            runBlocking(Dispatchers.IO) {
                // 第 1 级：精确匹配
                val exactMatch = dao.findByExactQuestion(trimmed)
                if (exactMatch != null) {
                    Log.d(TAG, "精确匹配成功: id=${exactMatch.id}")
                    return@runBlocking formatAnswer(exactMatch)
                }

                // 第 2 级：模糊匹配（取题干前 KEYWORD_LENGTH 字符）
                val keyword = trimmed.take(KEYWORD_LENGTH)
                val fuzzyMatches = dao.searchByKeyword("%$keyword%")
                if (fuzzyMatches.isNotEmpty()) {
                    Log.d(TAG, "模糊匹配成功: ${fuzzyMatches.size} 条结果")
                    return@runBlocking formatAnswer(fuzzyMatches.first())
                }

                // 第 3 级：兜底
                Log.d(TAG, "未找到匹配题目: ${trimmed.take(50)}...")
                "未找到匹配的题目，请确认题库已导入相关内容。\n\n搜索关键词：${trimmed.take(100)}..."
            }
        } catch (e: Exception) {
            Log.e(TAG, "题库检索异常", e)
            "题库检索失败: ${e.message}"
        }
    }

    /**
     * 格式化答案输出。
     */
    private fun formatAnswer(entity: QuestionEntity): String {
        val sb = StringBuilder()
        sb.append(entity.answer)

        if (entity.explanation.isNotBlank()) {
            sb.append("\n\n【解析】\n")
            sb.append(entity.explanation)
        }

        if (entity.subject.isNotBlank()) {
            sb.append("\n\n学科：${entity.subject}")
        }

        return sb.toString()
    }
}