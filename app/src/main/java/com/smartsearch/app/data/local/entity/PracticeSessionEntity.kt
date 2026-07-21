package com.smartsearch.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 练习会话实体 —— 映射 `practice_sessions` 表。
 *
 * 用于持久化用户中途退出后的练习进度，支持恢复继续答题。
 *
 * 字段说明：
 * - [id]：自增主键
 * - [subject]：所选学科，空字符串表示全部
 * - [mode]：练习模式，SEQUENTIAL 或 RANDOM
 * - [questionIds]：题目 ID 列表，JSON 数组格式，保持出题顺序
 * - [answers]：用户答案，JSON 对象格式 `{"questionId": selectedIndex, ...}`
 * - [bookmarks]：收藏的题目 ID，JSON 数组格式 `[questionId, ...]`
 * - [currentIndex]：当前答题进度（索引）
 * - [correctCount]：正确题数
 * - [answeredCount]：已答题数
 * - [startTime]：练习开始时间戳
 * - [isCompleted]：是否已完成
 */
@Entity(
    tableName = "practice_sessions",
    indices = [
        Index(value = ["start_time"]),
        Index(value = ["is_completed"])
    ]
)
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "subject")
    val subject: String = "",

    @ColumnInfo(name = "mode")
    val mode: String = "SEQUENTIAL",

    @ColumnInfo(name = "question_ids")
    val questionIds: String = "[]",

    @ColumnInfo(name = "answers")
    val answers: String = "{}",

    @ColumnInfo(name = "bookmarks")
    val bookmarks: String = "[]",

    @ColumnInfo(name = "current_index")
    val currentIndex: Int = 0,

    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,

    @ColumnInfo(name = "answered_count")
    val answeredCount: Int = 0,

    @ColumnInfo(name = "start_time")
    val startTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false
)