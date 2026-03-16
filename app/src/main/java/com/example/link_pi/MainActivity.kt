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
import com.example.link_pi.ui.navigation.Screen
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

        // SSH shortcut: redirect to independent SshActivity
        if (launchPage == Screen.SshHome.route) {
            SshActivity.launch(this)
            finish()
            return
        }

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

        // SSH: redirect to independent activity
        if (page == Screen.SshHome.route) {
            SshActivity.launch(this)
            return
        }

        pendingLaunchRequest = LaunchRequest(miniAppId, page)
    }
}