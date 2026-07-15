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
import com.smartsearch.app.core.permission.PermissionManager

/**
 * 权限引导页 —— 展示四项权限状态，引导用户逐项授权。
 *
 * 首次使用或权限缺失时跳转到此页面。
 * 用户授权后返回首页，权限状态自动刷新。
 */
class PermissionGuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                PermissionGuideScreen(
                    onGoToSetting = { permissionName ->
                        val intent = when (permissionName) {
                            "floating_window" -> PermissionManager.getFloatingWindowSettingsIntent(this)
                            "accessibility" -> PermissionManager.getAccessibilitySettingsIntent()
                            else -> null
                        }
                        if (intent != null) startActivity(intent)
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun PermissionGuideScreen(
    onGoToSetting: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("权限设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("搜题功能需要以下权限")
        Spacer(Modifier.height(24.dp))
        // 权限列表展示逻辑
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("返回") }
    }
}