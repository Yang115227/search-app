package com.smartsearch.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 错题实体 —— 映射错题表 `wrong_questions`。
 *
 * 字段说明：
 * - [id]：自增主键
 * - [questionId]：外键，关联 `questions` 表的 id
 * - [wrongCount]：错误次数累计
 * - [lastWrongTime]：最近一次错误时间戳
 * - [isMastered]：是否已掌握（掌握后不再出现在错题本中）
 */
@Entity(
    tableName = "wrong_questions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["question_id"], unique = true),
        Index(value = ["last_wrong_time"]),
        Index(value = ["is_mastered"])
    ]
)
data class WrongQuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "question_id")
    val questionId: Long,

    @ColumnInfo(name = "wrong_count")
    val wrongCount: Int = 1,

    @ColumnInfo(name = "last_wrong_time")
    val lastWrongTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_mastered")
    val isMastered: Boolean = false
)