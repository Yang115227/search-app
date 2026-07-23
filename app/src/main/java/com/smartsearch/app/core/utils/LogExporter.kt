package com.smartsearch.app.core.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具 —— 过滤 logcat 中指定 TAG 的日志并保存到文件。
 *
 * 使用方式：
 * ```
 * LogExporter.export(context)
 * ```
 *
 * 日志文件保存路径：
 * - Android 10+  (API 29+)：`外部存储/Android/data/<package>/files/Download/log_xxxx.txt`
 * - Android 4.4+ (API 19+)：`外部存储/Android/data/<package>/files/Download/log_xxxx.txt`
 * - 无需任何运行时权限即可写入。
 *
 * 过滤 TAG：
 * - 【SELECT_LOG】
 * - 【SCREEN_RECORD_LOG】
 * - 【DB_LOG】
 * - 【PRACTICE_LOG】
 */
object LogExporter {

    private const val TAG = "LogExporter"

    /** 需要捕获的日志 TAG 前缀列表 */
    private val TARGET_TAGS = listOf(
        "【SELECT_LOG】",
        "【SCREEN_RECORD_LOG】",
        "【DB_LOG】",
        "【PRACTICE_LOG】"
    )

    /** 日志文件名时间戳格式 */
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 导出日志到文件。
     *
     * @param context 上下文
     * @return 日志文件路径，失败返回 null
     */
    fun export(context: Context): String? {
        return try {
            // 1. 执行 logcat 命令，获取带时间戳的完整日志
            val logLines = captureLogcat()

            // 2. 过滤出目标 TAG 的日志
            val filteredLines = logLines.filter { line ->
                TARGET_TAGS.any { tag -> line.contains(tag) }
            }

            if (filteredLines.isEmpty()) {
                Log.w(TAG, "未找到匹配的日志记录")
                return null
            }

            // 3. 写入文件
            val file = createLogFile(context)
            file.bufferedWriter().use { writer ->
                writer.write("===== 搜题APP日志导出 =====\n")
                writer.write("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("过滤 TAG: ${TARGET_TAGS.joinToString(", ")}\n")
                writer.write("========================================\n\n")
                filteredLines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }

            Log.d(TAG, "日志已导出: ${file.absolutePath} (${filteredLines.size} 行)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "导出日志异常: ${e.message}", e)
            null
        }
    }

    /**
     * 导出日志并弹出分享 Intent。
     *
     * @param context 上下文
     */
    fun exportAndShare(context: Context) {
        val path = export(context)
        if (path == null) {
            android.widget.Toast.makeText(context, "未找到目标日志，请先复现操作", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享日志文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            android.widget.Toast.makeText(context, "日志已导出: $path", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "分享日志异常: ${e.message}", e)
            // 分享失败，至少告知文件路径
            android.widget.Toast.makeText(context, "日志已保存到: $path", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 执行 logcat 命令，获取所有日志。
     * 使用 `-d` 选项：dump 并退出（不阻塞）。
     * 使用 `-v time` 格式：带时间戳。
     */
    private fun captureLogcat(): List<String> {
        val lines = mutableListOf<String>()
        try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "time"
            ).redirectErrorStream(true).start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lines.add(line!!)
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "执行 logcat 命令异常: ${e.message}", e)
        }
        return lines
    }

    /**
     * 创建日志文件，路径为 `外部存储/Android/data/<package>/files/Download/log_<timestamp>.txt`。
     */
    private fun createLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir // 外部存储不可用时回退到内部 files 目录

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val timestamp = TIMESTAMP_FORMAT.format(Date())
        return File(dir, "log_$timestamp.txt")
    }
}