package com.smartsearch.app.data.local.dao

import androidx.room.*
import com.smartsearch.app.data.local.entity.PracticeRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 练习记录 DAO —— 练习历史增删改查与统计。
 */
@Dao
interface PracticeRecordDao {

    /** 插入练习记录 */
    @Insert
    suspend fun insert(record: PracticeRecordEntity): Long

    /** 按 ID 删除 */
    @Delete
    suspend fun delete(record: PracticeRecordEntity)

    // ==================== 查询 ====================

    /** 获取最近 N 条练习记录 */
    @Query("SELECT * FROM practice_records ORDER BY practice_time DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int = 20): List<PracticeRecordEntity>

    /** 获取所有练习记录 */
    @Query("SELECT * FROM practice_records ORDER BY practice_time DESC")
    suspend fun getAllRecords(): List<PracticeRecordEntity>

    /** 获取练习总次数 */
    @Query("SELECT COUNT(*) FROM practice_records")
    suspend fun getTotalPracticeCount(): Int

    // ==================== 统计 ====================

    /** 获取平均正确率 */
    @Query("SELECT AVG(accuracy) FROM practice_records")
    suspend fun getAverageAccuracy(): Float?

    /** 获取总练习时长（秒） */
    @Query("SELECT SUM(duration_seconds) FROM practice_records")
    suspend fun getTotalDuration(): Long?

    /** 按学科统计练习次数 */
    @Query("""
        SELECT subject, COUNT(*) as count 
        FROM practice_records 
        WHERE subject != '' 
        GROUP BY subject 
        ORDER BY count DESC
    """)
    suspend fun getPracticeCountBySubject(): List<SubjectCount>

    // ==================== Flow 响应式 ====================

    /** 流式获取最近练习记录 */
    @Query("SELECT * FROM practice_records ORDER BY practice_time DESC LIMIT 20")
    fun observeRecentRecords(): Flow<List<PracticeRecordEntity>>

    /** 流式获取练习总次数 */
    @Query("SELECT COUNT(*) FROM practice_records")
    fun observeTotalPracticeCount(): Flow<Int>
}

/**
 * 学科统计 POJO。
 */
data class SubjectCount(
    val subject: String,
    val count: Int
)