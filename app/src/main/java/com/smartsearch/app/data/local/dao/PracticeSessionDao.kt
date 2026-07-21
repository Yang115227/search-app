package com.smartsearch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.smartsearch.app.data.local.entity.PracticeSessionEntity

/**
 * 练习会话 DAO —— 练习进度持久化与恢复。
 */
@Dao
interface PracticeSessionDao {

    /** 创建新练习会话 */
    @Insert
    suspend fun insert(session: PracticeSessionEntity): Long

    /** 更新练习会话（保存进度） */
    @Update
    suspend fun update(session: PracticeSessionEntity)

    /** 删除练习会话 */
    @Delete
    suspend fun delete(session: PracticeSessionEntity)

    /** 获取最近一次未完成的练习会话（用于恢复） */
    @Query("SELECT * FROM practice_sessions WHERE is_completed = 0 ORDER BY start_time DESC LIMIT 1")
    suspend fun getLatestIncompleteSession(): PracticeSessionEntity?

    /** 按 ID 查询 */
    @Query("SELECT * FROM practice_sessions WHERE id = :id")
    suspend fun getById(id: Long): PracticeSessionEntity?

    /** 清空所有练习会话 */
    @Query("DELETE FROM practice_sessions")
    suspend fun deleteAll()