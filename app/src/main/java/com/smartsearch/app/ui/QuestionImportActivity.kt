package com.smartsearch.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 题库导入页 —— 选择 Excel 文件并导入到本地数据库。
 *
 * 支持 .xlsx / .xls 格式，通过 [ExcelImporter] 解析并写入 Room 数据库。
 */
class QuestionImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                QuestionImportScreen(
                    onPickFile = {
                        // TODO: 调用文件选择器 + ExcelImporter.importFromUri()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun QuestionImportScreen(
    onPickFile: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("题库导入", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("选择 Excel 文件导入题库")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFile) { Text("选择文件") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("返回") }
    }
}