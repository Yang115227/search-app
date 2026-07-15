package com.smartsearch.app.data.local.dao

import androidx.room.*
import com.smartsearch.app.data.local.entity.WrongQuestionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 错题 DAO —— 错题本增删改查。
 */
@Dao
interface WrongQuestionDao {

    /** 插入或更新错题记录（已存在则累加错误次数） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wrongQuestion: WrongQuestionEntity): Long

    /**
     * 记录一次答题错误。
     * 如果该题目已有错题记录，则累加错误次数 + 更新最后错误时间；
     * 否则创建新记录。
     */
    @Query("""
        INSERT INTO wrong_questions (question_id, wrong_count, last_wrong_time, is_mastered)
        VALUES (:questionId, 1, :time, 0)
        ON CONFLICT(question_id) DO UPDATE SET
            wrong_count = wrong_count + 1,
            last_wrong_time = :time,
            is_mastered = 0
    """)
    suspend fun recordWrong(questionId: Long, time: Long = System.currentTimeMillis())

    /** 按 ID 删除 */
    @Delete
    suspend fun delete(wrongQuestion: WrongQuestionEntity)

    /** 按题目 ID 删除错题记录 */
    @Query("DELETE FROM wrong_questions WHERE question_id = :questionId")
    suspend fun deleteByQuestionId(questionId: Long)

    /** 标记题目为"已掌握" */
    @Query("UPDATE wrong_questions SET is_mastered = 1 WHERE question_id = :questionId")
    suspend fun markMastered(questionId: Long)

    // ==================== 查询 ====================

    /** 获取所有未掌握的错题（按错误次数降序、最近错误时间降序） */
    @Query("""
        SELECT * FROM wrong_questions 
        WHERE is_mastered = 0 
        ORDER BY wrong_count DESC, last_wrong_time DESC
    """)
    suspend fun getUnmasteredWrongQuestions(): List<WrongQuestionEntity>

    /** 获取所有错题（含已掌握） */
    @Query("SELECT * FROM wrong_questions ORDER BY last_wrong_time DESC")
    suspend fun getAllWrongQuestions(): List<WrongQuestionEntity>

    /** 获取错题总数 */
    @Query("SELECT COUNT(*) FROM wrong_questions WHERE is_mastered = 0")
    suspend fun getWrongCount(): Int

    /** 按题目 ID 查询错题记录 */
    @Query("SELECT * FROM wrong_questions WHERE question_id = :questionId LIMIT 1")
    suspend fun findByQuestionId(questionId: Long): WrongQuestionEntity?

    // ==================== Flow 响应式 ====================

    /** 流式获取未掌握错题列表 */
    @Query("""
        SELECT * FROM wrong_questions 
        WHERE is_mastered = 0 
        ORDER BY wrong_count DESC, last_wrong_time DESC
    """)
    fun observeUnmasteredWrongQuestions(): Flow<List<WrongQuestionEntity>>

    /** 流式获取错题总数 */
    @Query("SELECT COUNT(*) FROM wrong_questions WHERE is_mastered = 0")
    fun observeWrongCount(): Flow<Int>
}