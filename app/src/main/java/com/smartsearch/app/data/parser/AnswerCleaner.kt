package com.smartsearch.app.data.parser

import android.util.Log

/**
 * 答案清洗工具类 —— 统一清洗规则，供 Excel 导入、OCR 识别、答题判分复用。
 *
 * # 清洗规则
 * 1. 去除首尾空白字符和换行
 * 2. 全角字符转半角
 * 3. 剔除「答案：」「答案:」「【】」等冗余标记
 * 4. 选择题答案统一大写（A/B/C/D 等）
 * 5. 多空格合并为单个空格
 * 6. 清洗后为空答案的拦截（返回空字符串）
 *
 * # 使用方式
 * ```kotlin
 * val cleaned = AnswerCleaner.clean(rawAnswer)
 * val cleanedWithException = AnswerCleaner.cleanSafe(rawAnswer) // 异常安全
 * val isCorrect = AnswerCleaner.compare(userAnswer, correctAnswer) // 判分比较
 * ```
 */
object AnswerCleaner {

    private const val TAG = "AnswerCleaner"

    /** 选择题答案匹配正则：纯字母答案（A、B、C、D 等） */
    private val CHOICE_ANSWER_PATTERN = Regex("^[A-Za-z]+$")

    /** 冗余标记正则：匹配「答案：」「答案:」「答案：」「【答案】」「【解析】」等 */
    private val MARKER_PATTERN = Regex(
        "[答案答案]?[：:：]?|" +
                "【[^】]*】|" +
                "（[^）]*）|" +
                "\\([^)]*\\)"
    )

    // ==================== 公开 API ====================

    /**
     * 清洗答案文本。
     *
     * @param input 原始答案文本
     * @return 清洗后的答案；如果清洗后为空则返回空字符串
     */
    fun clean(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var result = input

        // 1. 去除首尾空白和换行
        result = result.trim()

        // 2. 全角转半角
        result = fullWidthToHalfWidth(result)

        // 3. 剔除冗余标记
        result = result.replace(MARKER_PATTERN, "")

        // 4. 选择题答案统一大写
        result = result.trim().let { trimmed ->
            if (CHOICE_ANSWER_PATTERN.matches(trimmed)) {
                trimmed.uppercase()
            } else {
                trimmed
            }
        }

        // 5. 多空格合并
        result = result.replace(Regex("\\s+"), " ")

        // 6. 再次去除首尾空白
        result = result.trim()

        return result
    }

    /**
     * 异常安全的清洗方法。
     * 如果清洗过程中抛出异常，返回原始输入并记录日志。
     */
    fun cleanSafe(input: String?): String {
        return try {
            clean(input)
        } catch (e: Exception) {
            Log.e(TAG, "答案清洗异常: ${e.message}", e)
            input?.trim() ?: ""
        }
    }

    /**
     * 比较用户答案与正确答案是否匹配。
     * 双方都经过清洗后再比较，支持容错。
     *
     * @param userAnswer 用户输入的答案
     * @param correctAnswer 标准答案
     * @return true 如果匹配
     */
    fun compare(userAnswer: String?, correctAnswer: String?): Boolean {
        if (userAnswer.isNullOrBlank() || correctAnswer.isNullOrBlank()) return false
        try {
            val cleanedUser = clean(userAnswer)
            val cleanedCorrect = clean(correctAnswer)
            if (cleanedUser.isBlank() || cleanedCorrect.isBlank()) return false
            return cleanedUser == cleanedCorrect
        } catch (e: Exception) {
            Log.e(TAG, "答案比较异常: ${e.message}", e)
            return false
        }
    }

    /**
     * 批量清洗并收集异常行号。
     *
     * @param inputs 原始答案文本列表（附带行号信息）
     * @param mode 导入模式（严格/宽松）
     * @return 清洗结果
     */
    fun cleanBatch(inputs: List<CleanInput>, mode: ImportMode): CleanBatchResult {
        val cleaned = mutableListOf<Pair<Int, String>>() // (rowIndex, cleaned_text)
        val errorRows = mutableListOf<Int>()

        for ((rowIndex, rawText) in inputs) {
            try {
                val result = clean(rawText)
                if (result.isBlank()) {
                    errorRows.add(rowIndex)
                    if (mode == ImportMode.STRICT) {
                        // 严格模式：空答案行直接跳过，不加入结果
                        continue
                    }
                    // 宽松模式：空答案也加入（由调用方决定是否过滤）
                }
                cleaned.add(rowIndex to result)
            } catch (e: Exception) {
                Log.e(TAG, "第 $rowIndex 行清洗异常: ${e.message}", e)
                errorRows.add(rowIndex)
            }
        }

        return CleanBatchResult(cleaned, errorRows)
    }

    // ==================== 内部工具方法 ====================

    /**
     * 全角字符转半角字符。
     *
     * 规则：
     * - 全角字母、数字、标点 → 半角
     * - 全角空格（U+3000）→ 半角空格（U+0020）
     * - 全角句号「。」保留
     * - 其他全角字符保留
     */
    private fun fullWidthToHalfWidth(input: String): String {
        val sb = StringBuilder(input.length)
        for (char in input) {
            val code = char.code
            when {
                // 全角字母、数字、符号（U+FF01 ~ U+FF5E）→ 半角（U+0021 ~ U+007E）
                code in 0xFF01..0xFF5E -> {
                    sb.append((code - 0xFEE0).toChar())
                }
                // 全角空格（U+3000）→ 半角空格
                code == 0x3000 -> {
                    sb.append(' ')
                }
                // 全角句号「。」→ 半角句号「.」
                code == 0x3002 -> {
                    sb.append('.')
                }
                // 其他字符保留
                else -> {
                    sb.append(char)
                }
            }
        }
        return sb.toString()
    }

    // ==================== 数据类 ====================

    /** 清洗输入 */
    data class CleanInput(val rowIndex: Int, val rawText: String)

    /** 批量清洗结果 */
    data class CleanBatchResult(
        val cleaned: List<Pair<Int, String>>,
        val errorRows: List<Int>
    )

    /** 导入模式 */
    enum class ImportMode {
        /** 严格模式：空答案行直接跳过，不加入结果 */
        STRICT,
        /** 宽松模式：空答案行也加入结果，由调用方决定是否过滤 */
        LENIENT
    }
}