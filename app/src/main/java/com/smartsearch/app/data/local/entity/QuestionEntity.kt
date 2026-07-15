package com.smartsearch.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题目实体 —— 映射题库表 `questions`。
 *
 * 字段说明：
 * - [id]：自增主键
 * - [subject]：学科（语文、数学、英语等），用于分类筛选
 * - [question]：题干完整文本
 * - [options]：选项文本，JSON 数组格式，如 `["A.xxx","B.yyy","C.zzz","D.www"]`
 * - [answer]：正确答案文本
 * - [explanation]：解析
 * - [source]：题目来源，如 "Excel导入"、"手动录入"
 * - [importedAt]：导入时间戳
 */
@Entity(
    tableName = "questions",
    indices = [
        Index(value = ["subject"]),
        Index(value = ["question"])
    ]
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "subject")
    val subject: String = "",

    @ColumnInfo(name = "question")
    val question: String,

    @ColumnInfo(name = "options")
    val options: String = "",

    @ColumnInfo(name = "answer")
    val answer: String,

    @ColumnInfo(name = "explanation")
    val explanation: String = "",

    @ColumnInfo(name = "source")
    val source: String = "Excel导入",

    @ColumnInfo(name = "imported_at")
    val importedAt: Long = System.currentTimeMillis()
)