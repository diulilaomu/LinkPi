package com.example.link_pi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.link_pi.bridge.RuntimeErrorCollector
import com.example.link_pi.miniapp.ShortcutHelper
import com.example.link_pi.ui.navigation.LinkPiApp
import com.example.link_pi.ui.theme.LinkpiTheme

data class LaunchRequest(
    val miniAppId: String? = null,
    val page: String? = null
)

class MainActivity : ComponentActivity() {

    var pendingLaunchRequest: LaunchRequest? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeErrorCollector.init(applicationContext)
        enableEdgeToEdge()
        val launchMiniAppId = ShortcutHelper.getMiniAppIdFromIntent(intent)
        val launchPage = ShortcutHelper.getLaunchPageFromIntent(intent)
        setContent {
            LinkpiTheme {
                LinkPiApp(launchMiniAppId = launchMiniAppId, launchPage = launchPage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val miniAppId = ShortcutHelper.getMiniAppIdFromIntent(intent)
        val page = ShortcutHelper.getLaunchPageFromIntent(intent)
        pendingLaunchRequest = LaunchRequest(miniAppId, page)
    }
}