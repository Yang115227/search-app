package com.smartsearch.app.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.smartsearch.app.data.local.QuizDatabase
import com.smartsearch.app.data.local.entity.QuestionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

/**
 * Excel 题库导入解析器 —— 读取 .xlsx 文件，解析行列映射为 QuestionEntity 并批量写入 Room。
 *
 * 支持两种导入模式：
 * - **严格模式（STRICT）**：答案清洗后为空的行直接跳过，不导入
 * - **宽松模式（LENIENT）**：答案清洗后为空的行也收集，但仅做日志记录，不中断导入
 *
 * 答案清洗规则详见 [AnswerCleaner]。
 *
 * # 支持的 Excel 格式
 * - .xlsx（Office Open XML）
 * - .xls（旧版格式，通过 WorkbookFactory 兼容）
 *
 * # Excel 列映射规则
 * 支持两种模式：
 * 1. **自动检测表头**：第一行为表头，自动匹配列名（支持中英文别名）
 * 2. **固定列序**：无表头时按固定列序读取（A=题干, B=答案, C=解析, D=选项, E=学科）
 */
object ExcelImporter {

    private const val TAG = "ExcelImporter"

    /** 单次导入最大行数（防止 OOM） */
    private const val MAX_ROWS = 5000

    /** 批次写入大小 */
    private const val BATCH_SIZE = 100

    // ==================== 列名映射 ====================

    /** 列名别名映射：支持中英文多种写法 */
    private val COLUMN_ALIASES = mapOf(
        "题干" to ColumnType.QUESTION,
        "题目" to ColumnType.QUESTION,
        "question" to ColumnType.QUESTION,
        "答案" to ColumnType.ANSWER,
        "answer" to ColumnType.ANSWER,
        "解析" to ColumnType.EXPLANATION,
        "explanation" to ColumnType.EXPLANATION,
        "选项" to ColumnType.OPTIONS,
        "options" to ColumnType.OPTIONS,
        "学科" to ColumnType.SUBJECT,
        "subject" to ColumnType.SUBJECT,
        "科目" to ColumnType.SUBJECT
    )

    private enum class ColumnType {
        QUESTION, ANSWER, EXPLANATION, OPTIONS, SUBJECT
    }

    // ==================== 导入结果 ====================

    /** 导入结果密封类 */
    sealed class ImportResult {
        /**
         * 导入成功。
         * @param count 成功导入的题目数
         * @param errorRows 清洗后为空或格式异常的 Excel 行号列表（从 1 开始）
         */
        data class Success(
            val count: Int,
            val errorRows: List<Int> = emptyList()
        ) : ImportResult()

        /**
         * 导入失败。
         * @param message 错误描述
         * @param errorRows 清洗后为空或格式异常的 Excel 行号列表（从 1 开始）
         */
        data class Error(
            val message: String,
            val errorRows: List<Int> = emptyList()
        ) : ImportResult()
    }

    /** 常见表头关键词列表（用于模糊匹配，当精确匹配失败时兜底检测） */
    private val HEADER_KEYWORDS = listOf(
        "题", "题目", "题干", "问题", "question",
        "答", "答案", "正确", "answer",
        "选", "选项", "options",
        "解析", "解释", "explanation",
        "科", "学科", "科目", "subject",
        "序号", "编号", "id", "no"
    )

    /** 判断文本是否看起来像表头（基于关键词模糊匹配） */
    private fun looksLikeHeader(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length > 20) return false // 过长的文本不可能是表头
        return HEADER_KEYWORDS.any { keyword ->
            lower.contains(keyword) || keyword.contains(lower)
        }
    }

    // ==================== 公开 API ====================

    /**
     * 清理数据库中已知的表头行脏数据。
     * 当之前的导入把 Excel 表头行误作为题目导入时，调用此方法删除。
     */
    suspend fun cleanupHeaderRows(context: Context) {
        val db = QuizDatabase.getInstance(context)
        val dao = db.questionDao()
        withContext(Dispatchers.IO) {
            val allQuestions = dao.getAllQuestions()
            val headerQuestions = allQuestions.filter { q ->
                q.question.isNotBlank() && q.question.length <= 15 &&
                        KNOWN_HEADER_TEXTS.any { header ->
                            q.question.trim().lowercase() == header
                        }
            }
            if (headerQuestions.isNotEmpty()) {
                Log.w(TAG, "清理 ${headerQuestions.size} 条表头行脏数据")
                headerQuestions.forEach { dao.delete(it) }
            }
        }
    }

    /**
     * 从 Uri 导入 Excel 题库。
     *
     * @param context 上下文
     * @param uri Excel 文件的 Content Uri（如从文件选择器获取）
     * @param defaultSubject 默认学科，非空时覆盖文件中每道题的学科（或填补空学科）
     * @param importMode 导入模式：严格模式（STRICT）空答案行跳过，宽松模式（LENIENT）仅记录不跳过
     * @return [ImportResult]
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        defaultSubject: String = "",
        importMode: AnswerCleaner.ImportMode = AnswerCleaner.ImportMode.STRICT
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("无法打开文件，请确保文件未被其他程序占用")

            val result = parseAndImport(inputStream, context, defaultSubject, importMode)
            inputStream.close()
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "文件读取权限不足", e)
            ImportResult.Error("文件读取权限不足，请重新选择文件")
        } catch (e: Exception) {
            Log.e(TAG, "Excel 解析失败", e)
            ImportResult.Error("Excel 解析失败: ${e.message}")
        }
    }

    // ==================== 解析与导入 ====================

    /**
     * 解析 Excel 输入流并批量写入数据库。
     */
    private fun parseAndImport(
        inputStream: InputStream,
        context: Context,
        defaultSubject: String = "",
        importMode: AnswerCleaner.ImportMode = AnswerCleaner.ImportMode.STRICT
    ): ImportResult {
        val workbook: Workbook = try {
            WorkbookFactory.create(inputStream)
        } catch (e: Exception) {
            return ImportResult.Error("文件格式不支持，请使用 .xlsx 或 .xls 格式的 Excel 文件")
        }

        workbook.use { wb ->
            if (wb.numberOfSheets == 0) {
                return ImportResult.Error("Excel 文件中没有工作表，请检查文件内容")
            }

            val sheet = wb.getSheetAt(0) // 默认读取第一个工作表
            val rowCount = sheet.physicalNumberOfRows

            if (rowCount <= 0) {
                return ImportResult.Error("工作表为空，没有数据行")
            }

            // 验证 Excel 格式：检查第一行是否至少有 2 列
            val firstRow = sheet.getRow(0)
            if (firstRow == null || firstRow.lastCellNum.toInt() < 2) {
                return ImportResult.Error("Excel 文件格式错误：至少需要包含「题干」和「答案」两列，请检查文件内容")
            }

            // 检查第一行是否包含可识别的表头
            val headerTexts = mutableListOf<String>()
            for (cellIndex in 0 until firstRow.lastCellNum.coerceAtMost(20)) {
                val cell = firstRow.getCell(cellIndex) ?: continue
                val text = getCellString(cell).trim().lowercase()
                if (text.isNotBlank()) {
                    headerTexts.add(text)
                }
            }
            val hasRecognizableHeader = headerTexts.any { COLUMN_ALIASES.containsKey(it) }
            if (!hasRecognizableHeader) {
                Log.w(TAG, "第一行未识别到已知表头，将使用固定列序模式（A=题干, B=答案）")
            }

            // 限制最大行数
            val effectiveRows = minOf(rowCount, MAX_ROWS + 1) // +1 留表头
            if (rowCount > MAX_ROWS + 1) {
                Log.w(TAG, "Excel 行数($rowCount)超过限制($MAX_ROWS)，仅导入前 $MAX_ROWS 行")
            }

            // 解析表头
            val headerRow = sheet.getRow(0)
            val columnMapping = parseHeader(headerRow)

            // 检查表头映射是否至少包含「题干」和「答案」两列
            if (columnMapping.isNotEmpty()) {
                val hasQuestion = columnMapping.values.contains(ColumnType.QUESTION)
                val hasAnswer = columnMapping.values.contains(ColumnType.ANSWER)
                if (!hasQuestion || !hasAnswer) {
                    return ImportResult.Error("Excel 表头至少需要包含「题干」和「答案」两列，请检查表头命名是否正确")
                }
            }

            // 确定数据起始行
            // 有表头映射时从第 2 行（索引 1）开始，无表头映射时：
            //   1. 如果第一行内容看起来像表头（短文本、含关键词），则跳过第一行
            //   2. 否则从第 1 行（索引 0）开始
            var dataStartRow: Int
            if (columnMapping.isNotEmpty()) {
                dataStartRow = 1
            } else {
                // 无表头映射时，启发式检测第一行是否像表头
                val firstRow = sheet.getRow(0)
                val firstRowLooksLikeHeader = firstRow?.let { row ->
                    val nonEmptyCells = (0 until row.lastCellNum.coerceAtMost(10)).mapNotNull { idx ->
                        val cell = row.getCell(idx)
                        val text = getCellString(cell).trim()
                        text.ifBlank { null }
                    }
                    // 第一行多数单元格短文本且含关键词 → 判定为表头
                    nonEmptyCells.isNotEmpty() && nonEmptyCells.all { it.length <= 15 && looksLikeHeader(it) }
                } ?: false

                dataStartRow = if (firstRowLooksLikeHeader) {
                    Log.w(TAG, "第一行内容看起来像表头，自动跳过（使用固定列序模式从第2行开始读取）")
                    1
                } else {
                    0
                }
            }

            // 解析数据行
            val questions = mutableListOf<QuestionEntity>()
            val allErrorRows = mutableListOf<Int>()

            for (rowIndex in dataStartRow until effectiveRows) {
                val row = sheet.getRow(rowIndex) ?: continue
                if (isRowEmpty(row)) continue

                val result = parseRow(row, columnMapping, rowIndex, defaultSubject, importMode)
                when (result) {
                    is ParseRowResult.Success -> questions.add(result.entity)
                    is ParseRowResult.Skipped -> {
                        // 跳过行：记录行号
                        allErrorRows.add(result.rowNumber)
                        if (result.reason.isNotBlank()) {
                            Log.w(TAG, "第 ${result.rowNumber} 行跳过: ${result.reason}")
                        }
                    }
                }
            }

            if (questions.isEmpty()) {
                val errorDetail = if (allErrorRows.isNotEmpty()) {
                    "（异常行: ${allErrorRows.joinToString(", ")}）"
                } else ""
                return ImportResult.Error("未解析到有效题目数据，请确保 Excel 包含「题干」和「答案」两列$errorDetail", allErrorRows)
            }

            // 批量写入数据库
            val db = QuizDatabase.getInstance(context)
            val dao = db.questionDao()

            // 分批写入，避免单次事务过大
            var importedCount = 0
            questions.chunked(BATCH_SIZE).forEach { batch ->
                runBlocking(Dispatchers.IO) {
                    dao.insertAll(batch)
                }
                importedCount += batch.size
            }

            Log.d(TAG, "Excel 导入完成: $importedCount 条题目，${allErrorRows.size} 行异常跳过")
            return ImportResult.Success(importedCount, allErrorRows)
        }
    }

    // ==================== 表头解析 ====================

    /**
     * 已知表头关键词列表（用于精确匹配）。
     */
    private val KNOWN_HEADER_TEXTS = COLUMN_ALIASES.keys.toSet()

    /**
     * 解析表头行，返回 列索引 → 列类型 的映射。
     *
     * 策略：
     * 1. 遍历表头行每个单元格
     * 2. 将单元格文本与 [COLUMN_ALIASES] 匹配
     * 3. 返回匹配到的列索引映射
     *
     * 如果没有找到"题干"列，但找到了其他列类型，仍返回部分映射（不强制要求"题干"列），
     * 由调用方决定是否使用固定列序模式。
     */
    private fun parseHeader(headerRow: Row?): Map<Int, ColumnType> {
        if (headerRow == null) return emptyMap()

        val mapping = mutableMapOf<Int, ColumnType>()
        for (cellIndex in 0 until headerRow.lastCellNum.coerceAtMost(20)) {
            val cell = headerRow.getCell(cellIndex) ?: continue
            val headerText = getCellString(cell).trim().lowercase()
            val type = COLUMN_ALIASES[headerText]
            if (type != null) {
                mapping[cellIndex] = type
            }
        }

        // 至少找到一列可识别的表头才视为有效表头映射
        if (mapping.isEmpty()) {
            Log.w(TAG, "表头中未找到任何已知列名，将使用固定列序模式")
            return emptyMap()
        }

        // 如果没有找到"题干"列，但找到了其他列，记录警告但仍使用表头映射
        if (!mapping.values.contains(ColumnType.QUESTION)) {
            Log.w(TAG, "表头中未找到'题干'列，将根据已识别的列映射读取（缺少"题干"时题干字段将为空）")
        }

        return mapping
    }

    // ==================== 数据行解析 ====================

    /** 行解析结果 */
    private sealed class ParseRowResult {
        data class Success(val entity: QuestionEntity) : ParseRowResult()
        data class Skipped(val rowNumber: Int, val reason: String = "") : ParseRowResult()
    }

    /**
     * 解析单行数据为 [QuestionEntity]。
     *
     * 答案字段会经过 [AnswerCleaner] 清洗：
     * - 去除首尾空白换行
     * - 全角转半角
     * - 剔除「答案：」「【】」等冗余标记
     * - 选择题答案统一大写
     * - 多空格合并
     * - 清洗后为空答案的行拦截跳过
     *
     * @param row Excel 行对象
     * @param columnMapping 表头列映射（为空则使用固定列序：A=题干, B=答案, C=解析, D=选项, E=学科）
     * @param rowIndex 行号（从 0 开始，用于日志）
     * @param defaultSubject 默认学科
     * @param importMode 导入模式
     */
    private fun parseRow(
        row: Row,
        columnMapping: Map<Int, ColumnType>,
        rowIndex: Int,
        defaultSubject: String = "",
        importMode: AnswerCleaner.ImportMode = AnswerCleaner.ImportMode.STRICT
    ): ParseRowResult {
        try {
            val question: String
            val answer: String
            var explanation = ""
            var options = ""
            var subject = ""

            if (columnMapping.isNotEmpty()) {
                // 模式 1：按表头映射读取
                question = getCellValueByType(row, columnMapping, ColumnType.QUESTION)
                answer = getCellValueByType(row, columnMapping, ColumnType.ANSWER)
                explanation = getCellValueByType(row, columnMapping, ColumnType.EXPLANATION)
                options = getCellValueByType(row, columnMapping, ColumnType.OPTIONS)
                subject = getCellValueByType(row, columnMapping, ColumnType.SUBJECT)
            } else {
                // 模式 2：固定列序 A=题干, B=答案, C=解析, D=选项, E=学科
                question = getCellString(row.getCell(0))
                answer = getCellString(row.getCell(1))
                explanation = getCellString(row.getCell(2))
                options = getCellString(row.getCell(3))
                subject = getCellString(row.getCell(4))
            }

            // 如果用户指定了默认学科，覆盖文件中每道题的学科
            if (defaultSubject.isNotBlank()) {
                subject = defaultSubject
            }

            // 题干不能为空
            if (question.isBlank()) {
                return ParseRowResult.Skipped(rowIndex + 1, "题干为空")
            }

            // 答案清洗
            val cleanedAnswer = AnswerCleaner.cleanSafe(answer)

            // 清洗后答案为空
            if (cleanedAnswer.isBlank()) {
                if (importMode == AnswerCleaner.ImportMode.STRICT) {
                    return ParseRowResult.Skipped(rowIndex + 1, "答案清洗后为空")
                }
                // 宽松模式：不跳过，但记录警告
                Log.w(TAG, "第 ${rowIndex + 1} 行答案清洗后为空（宽松模式，仍导入）")
            }

            return ParseRowResult.Success(
                QuestionEntity(
                    question = question.trim(),
                    answer = cleanedAnswer.ifBlank { answer.trim() },
                    explanation = explanation.trim(),
                    options = options.trim(),
                    subject = subject.trim(),
                    source = "Excel导入",
                    importedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Excel 文件格式错误：第 ${rowIndex + 1} 行数据格式异常，请检查", e)
            return ParseRowResult.Skipped(rowIndex + 1, "数据格式异常: ${e.message}")
        }
    }

    /**
     * 根据列类型从映射中获取单元格值。
     */
    private fun getCellValueByType(
        row: Row,
        columnMapping: Map<Int, ColumnType>,
        type: ColumnType
    ): String {
        val cellIndex = columnMapping.entries
            .firstOrNull { it.value == type }?.key ?: return ""
        val cell = row.getCell(cellIndex) ?: return ""
        return getCellString(cell)
    }

    // ==================== 单元格读取 ====================

    /**
     * 安全读取单元格字符串值，适配各种类型。
     */
    private fun getCellString(cell: Cell?): String {
        if (cell == null) return ""

        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> {
                    // 判断是否为整数：如果小数部分为 0，按整数格式输出
                    val num = cell.numericCellValue
                    if (num == num.toLong().toDouble()) {
                        num.toLong().toString()
                    } else {
                        num.toString()
                    }
                }
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                CellType.FORMULA -> {
                    // 公式单元格：尝试获取缓存值，否则计算
                    try {
                        cell.stringCellValue
                    } catch (e: Exception) {
                        cell.numericCellValue.toString()
                    }
                }
                CellType.BLANK -> ""
                CellType.ERROR -> ""
                else -> cell.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取单元格异常: ${e.message}")
            ""
        }
    }

    /**
     * 判断行是否为空（所有单元格均无内容）。
     */
    private fun isRowEmpty(row: Row): Boolean {
        for (cellIndex in 0 until row.lastCellNum.coerceAtMost(20)) {
            val cell = row.getCell(cellIndex)
            if (cell != null && getCellString(cell).isNotBlank()) {
                return false
            }
        }
        return true
    }
}