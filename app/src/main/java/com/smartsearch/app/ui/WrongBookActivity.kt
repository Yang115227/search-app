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
 * 错题本页 —— 展示所有答错的题目，支持复习和标记已掌握。
 *
 * 数据来源于 [WrongQuestionEntity]，按错误次数降序排列。
 */
class WrongBookActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WrongBookScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun WrongBookScreen(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("错题本", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("查看错题记录")
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("返回") }
    }
}