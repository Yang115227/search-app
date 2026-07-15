package com.smartsearch.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 练习记录实体 —— 映射练习记录表 `practice_records`。
 *
 * 字段说明：
 * - [id]：自增主键
 * - [subject]：练习学科（为空表示全部学科）
 * - [totalQuestions]：本次练习总题数
 * - [correctCount]：正确题数
 * - [accuracy]：正确率（0.0 ~ 1.0）
 * - [durationSeconds]：练习耗时（秒）
 * - [practiceTime]：练习时间戳
 */
@Entity(
    tableName = "practice_records",
    indices = [
        Index(value = ["practice_time"]),
        Index(value = ["subject"])
    ]
)
data class PracticeRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "subject")
    val subject: String = "",

    @ColumnInfo(name = "total_questions")
    val totalQuestions: Int = 0,

    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float = 0f,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Long = 0,

    @ColumnInfo(name = "practice_time")
    val practiceTime: Long = System.currentTimeMillis()
)