package com.smartsearch.app.data.local.dao

import androidx.room.*
import com.smartsearch.app.data.local.entity.QuestionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 题目 DAO —— 题库增删改查与检索。
 */
@Dao
interface QuestionDao {

    // ==================== 插入 ====================

    /** 插入单条题目 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(question: QuestionEntity): Long

    /** 批量插入题目，返回插入的 rowId 列表 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<QuestionEntity>): List<Long>

    // ==================== 删除 ====================

    /** 按 ID 删除 */
    @Delete
    suspend fun delete(question: QuestionEntity)

    /** 清空全表 */
    @Query("DELETE FROM questions")
    suspend fun deleteAll()

    /** 按学科删除所有题目 */
    @Query("DELETE FROM questions WHERE subject = :subject")
    suspend fun deleteBySubject(subject: String)

    /** 更新学科名称（重命名） */
    @Query("UPDATE questions SET subject = :newSubject WHERE subject = :oldSubject")
    suspend fun updateSubject(oldSubject: String, newSubject: String)

    // ==================== 查询 ====================

    /** 按 ID 精确查询 */
    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun findById(id: Long): QuestionEntity?

    /** 按题干完整精确匹配 */
    @Query("SELECT * FROM questions WHERE question = :question LIMIT 1")
    suspend fun findByExactQuestion(question: String): QuestionEntity?

    /** 按题干关键词模糊匹配（LIKE 查询），返回匹配度最高的前 10 条 */
    @Query("SELECT * FROM questions WHERE question LIKE :keyword LIMIT 10")
    suspend fun searchByKeyword(keyword: String): List<QuestionEntity>

    /** 按学科筛选所有题目 */
    @Query("SELECT * FROM questions WHERE subject = :subject")
    suspend fun findBySubject(subject: String): List<QuestionEntity>

    /** 获取所有学科列表（去重） */
    @Query("SELECT DISTINCT subject FROM questions WHERE subject != ''")
    suspend fun getAllSubjects(): List<String>

    /** 获取所有题目 */
    @Query("SELECT * FROM questions ORDER BY imported_at DESC")
    suspend fun getAllQuestions(): List<QuestionEntity>

    /** 获取题库总数 */
    @Query("SELECT COUNT(*) FROM questions")
    suspend fun getCount(): Int

    /** 按学科获取题目数量 */
    @Query("SELECT COUNT(*) FROM questions WHERE subject = :subject")
    suspend fun getCountBySubject(subject: String): Int

    // ==================== 随机出题 ====================

    /**
     * 随机获取指定数量的题目。
     *
     * Room 不直接支持 RANDOM() 的跨平台兼容性；
     * 使用 `ORDER BY RANDOM()` 是 SQLite 专用语法，Android 上可用。
     *
     * @param limit 题目数量
     * @return 随机题目列表
     */
    @Query("SELECT * FROM questions ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomQuestions(limit: Int): List<QuestionEntity>

    /**
     * 按学科随机获取题目。
     */
    @Query("SELECT * FROM questions WHERE subject = :subject ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomQuestionsBySubject(subject: String, limit: Int): List<QuestionEntity>

    // ==================== Flow 响应式查询 ====================

    /** 流式获取所有题目（用于 UI 列表实时更新） */
    @Query("SELECT * FROM questions ORDER BY imported_at DESC")
    fun observeAllQuestions(): Flow<List<QuestionEntity>>

    /** 流式获取题库总数 */
    @Query("SELECT COUNT(*) FROM questions")
    fun observeCount(): Flow<Int>
}