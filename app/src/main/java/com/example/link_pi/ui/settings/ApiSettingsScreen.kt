package com.example.link_pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.link_pi.network.AiConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen() {
    val context = LocalContext.current
    val config = remember { AiConfig(context) }

    var apiEndpoint by remember { mutableStateOf(config.apiEndpoint) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var modelName by remember { mutableStateOf(config.modelName) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "默认使用阿里百炼平台，也支持其他 OpenAI 兼容 API",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Quick presets
        Text(
            text = "快捷配置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiConfig.PRESETS.forEach { preset ->
                AssistChip(
                    onClick = {
                        apiEndpoint = preset.endpoint
                        modelName = preset.model
                        saved = false
                    },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        OutlinedTextField(
            value = apiEndpoint,
            onValueChange = {
                apiEndpoint = it
                saved = false
            },
            label = { Text("API 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                saved = false
            },
            label = { Text("API 密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text("百炼: 控制台 → API-KEY 管理 → 创建")
            }
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = {
                modelName = it
                saved = false
            },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text("qwen-max / qwen-plus / qwen-turbo / qwen-long")
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                config.apiEndpoint = apiEndpoint.trim()
                config.apiKey = apiKey.trim()
                config.modelName = modelName.trim()
                saved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saved) "\u2713 已保存" else "保存配置")
        }

        OutlinedButton(
            onClick = {
                config.resetToDefault()
                apiEndpoint = AiConfig.DEFAULT_ENDPOINT
                modelName = AiConfig.DEFAULT_MODEL
                saved = false
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("重置为默认")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "密钥仅存储在本地设备，支持 OpenAI、DeepSeek 等兼容接口",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
