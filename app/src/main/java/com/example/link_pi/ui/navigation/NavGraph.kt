package com.example.link_pi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.link_pi.ui.chat.ChatScreen
import com.example.link_pi.ui.chat.ChatViewModel
import com.example.link_pi.ui.miniapp.MiniAppListScreen
import com.example.link_pi.ui.miniapp.MiniAppScreen
import com.example.link_pi.ui.settings.ApiSettingsScreen
import com.example.link_pi.ui.settings.MemoryScreen
import com.example.link_pi.ui.settings.ModuleScreen
import com.example.link_pi.ui.settings.SettingsScreen
import com.example.link_pi.ui.skill.SkillListScreen

sealed class Screen(val route: String, val title: String) {
    data object Chat : Screen("chat", "LinkPi")
    data object Apps : Screen("apps", "应用")
    data object Settings : Screen("settings", "设置")
    data object MiniApp : Screen("miniapp", "运行应用")
    data object ApiSettings : Screen("settings/api", "API 配置")
    data object SkillSettings : Screen("settings/skills", "Skill 管理")
    data object MemorySettings : Screen("settings/memory", "长期记忆")
    data object ModuleSettings : Screen("settings/modules", "模块管理")
}

@Composable
fun LinkPiApp() {
    val chatViewModel: ChatViewModel = viewModel()
    var currentPage by remember { mutableStateOf<String>(Screen.Chat.route) }
    var lastBackTime by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current

    // Back handler: sub-pages go back to parent, main page requires double-back to exit
    BackHandler {
        when (currentPage) {
            Screen.Chat.route -> {
                val now = System.currentTimeMillis()
                if (now - lastBackTime < 2000) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBackTime = now
                    Toast.makeText(context, "再滑一次退出应用", Toast.LENGTH_SHORT).show()
                }
            }
            Screen.ApiSettings.route,
            Screen.SkillSettings.route,
            Screen.MemorySettings.route,
            Screen.ModuleSettings.route -> currentPage = Screen.Settings.route
            else -> currentPage = Screen.Chat.route
        }
    }

    // MiniApp has its own full-screen layout
    if (currentPage == Screen.MiniApp.route) {
        val app = chatViewModel.currentMiniApp
        if (app != null) {
            MiniAppScreen(miniApp = app, onBack = { currentPage = Screen.Chat.route })
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ── Fixed Top Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (currentPage) {
                Screen.Chat.route -> {
                    IconButton(onClick = { currentPage = Screen.Settings.route }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { currentPage = Screen.Apps.route }) {
                        Icon(Icons.Outlined.GridView, contentDescription = "应用")
                    }
                }
                else -> {
                    val title = when (currentPage) {
                        Screen.Apps.route -> Screen.Apps.title
                        Screen.Settings.route -> Screen.Settings.title
                        Screen.ApiSettings.route -> Screen.ApiSettings.title
                        Screen.SkillSettings.route -> Screen.SkillSettings.title
                        Screen.MemorySettings.route -> Screen.MemorySettings.title
                        Screen.ModuleSettings.route -> Screen.ModuleSettings.title
                        else -> ""
                    }
                    val backTarget = when (currentPage) {
                        Screen.ApiSettings.route,
                        Screen.SkillSettings.route,
                        Screen.MemorySettings.route,
                        Screen.ModuleSettings.route -> Screen.Settings.route
                        else -> Screen.Chat.route
                    }
                    IconButton(onClick = { currentPage = backTarget }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // ── Content Area (fills remaining space) ──
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentPage) {
                Screen.Chat.route -> ChatScreen(
                    viewModel = chatViewModel,
                    onRunApp = { app ->
                        chatViewModel.setCurrentApp(app)
                        currentPage = Screen.MiniApp.route
                    }
                )
                Screen.Apps.route -> MiniAppListScreen(
                    storage = chatViewModel.miniAppStorage,
                    onRunApp = { app ->
                        chatViewModel.setCurrentApp(app)
                        currentPage = Screen.MiniApp.route
                    }
                )
                Screen.Settings.route -> SettingsScreen(
                    onNavigate = { route -> currentPage = route }
                )
                Screen.ApiSettings.route -> ApiSettingsScreen()
                Screen.SkillSettings.route -> SkillListScreen(
                    skillStorage = chatViewModel.skillStorage,
                    activeSkillId = chatViewModel.activeSkill.value.id,
                    onSelectSkill = { skill -> chatViewModel.setActiveSkill(skill) }
                )
                Screen.MemorySettings.route -> MemoryScreen()
                Screen.ModuleSettings.route -> ModuleScreen()
            }
        }
    }
}
