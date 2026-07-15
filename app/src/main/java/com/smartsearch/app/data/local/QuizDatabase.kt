package com.smartsearch.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartsearch.app.data.local.dao.PracticeRecordDao
import com.smartsearch.app.data.local.dao.QuestionDao
import com.smartsearch.app.data.local.dao.WrongQuestionDao
import com.smartsearch.app.data.local.entity.PracticeRecordEntity
import com.smartsearch.app.data.local.entity.QuestionEntity
import com.smartsearch.app.data.local.entity.WrongQuestionEntity

/**
 * Room 数据库 —— 题库与练习记录的统一存储。
 *
 * # 版本管理
 * | 版本 | 变更内容                                    |
 * |------|--------------------------------------------|
 * | 1    | 初始版本：questions、wrong_questions、practice_records |
 *
 * # 线程安全
 * Room 数据库本身是线程安全的，但建议在 `Dispatchers.IO` 上调用 DAO 方法。
 * 所有 DAO 方法使用 `suspend` 或 `Flow` 以支持 Kotlin 协程。
 *
 * # 使用方式
 * ```kotlin
 * // 在 Application 中初始化
 * val db = QuizDatabase.getInstance(context)
 *
 * // 在 ViewModel 中使用
 * viewModelScope.launch(Dispatchers.IO) {
 *     val questions = db.questionDao().getRandomQuestions(10)
 * }
 * ```
 */
@Database(
    entities = [
        QuestionEntity::class,
        WrongQuestionEntity::class,
        PracticeRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class QuizDatabase : RoomDatabase() {

    abstract fun questionDao(): QuestionDao
    abstract fun wrongQuestionDao(): WrongQuestionDao
    abstract fun practiceRecordDao(): PracticeRecordDao

    companion object {
        private const val DATABASE_NAME = "quiz_database.db"

        @Volatile
        private var INSTANCE: QuizDatabase? = null

        /**
         * 获取数据库单例。
         *
         * 使用双重检查锁定（Double-Checked Locking）确保线程安全。
         * 在 Application.onCreate 中首次调用，后续调用直接返回已创建的实例。
         *
         * @param context Application 上下文
         * @return 数据库实例
         */
        fun getInstance(context: Context): QuizDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * 构建 Room 数据库实例。
         *
         * 在开发阶段使用 `fallbackToDestructiveMigration()`，
         * 生产环境应替换为具体的 [Migration] 策略。
         */
        private fun buildDatabase(context: Context): QuizDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                QuizDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration() // 开发阶段：升级时销毁重建
                // 生产环境应使用 .addMigrations(MIGRATION_1_2, ...)
                .build()
        }

        /**
         * 版本迁移策略示例（保留供后续版本使用）。
         *
         * ```kotlin
         * val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(database: SupportSQLiteDatabase) {
         *         database.execSQL("ALTER TABLE questions ADD COLUMN difficulty TEXT NOT NULL DEFAULT ''")
         *     }
         * }
         * ```
         */
    }
}