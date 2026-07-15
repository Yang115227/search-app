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
 * 练习页 —— 随机出题练习。
 *
 * 从题库中随机抽取题目，用户作答后即时反馈对错，
 * 记录练习结果到 [PracticeRecordEntity]。
 */
class PracticeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                PracticeScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun PracticeScreen(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("练习", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("随机出题练习")
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("返回") }
    }
}