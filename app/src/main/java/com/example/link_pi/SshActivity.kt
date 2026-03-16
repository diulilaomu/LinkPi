package com.example.link_pi

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.link_pi.data.session.AppSessionManager
import com.example.link_pi.data.session.AppType
import com.example.link_pi.ui.ssh.SshHomeScreen
import com.example.link_pi.ui.ssh.SshHomeViewModel
import com.example.link_pi.ui.ssh.SshScreen
import com.example.link_pi.ui.ssh.SshViewModel
import com.example.link_pi.ui.theme.LinkpiTheme

/**
 * Standalone Activity for the built-in SSH terminal.
 * Runs in its own Android task with a distinct taskAffinity,
 * giving it an independent card in the recent-apps screen —
 * just like MiniApp slots.
 *
 * Launch via [SshActivity.launch].
 */
class SshActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SshActivity"
        private const val EXTRA_SERVER_ID = "server_id"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_MANUAL_MODE = "manual_mode"

        fun launch(context: Context, serverId: String? = null) {
            val intent = Intent(context, SshActivity::class.java).apply {
                if (serverId != null) putExtra(EXTRA_SERVER_ID, serverId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(intent)
        }

        /** Launch directly into an existing SSH session (e.g. from ChatScreen). */
        fun launchSession(context: Context, sessionId: String, manualMode: Boolean = false) {
            val intent = Intent(context, SshActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_MANUAL_MODE, manualMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d(TAG, "onCreate: taskId=$taskId flags=${intent.flags.toString(16)}")

        applyTaskDescription("SSH 终端")

        val launchSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val launchManualMode = intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)

        setContent {
            LinkpiTheme {
                val sshViewModel: SshViewModel = viewModel()
                val sshHomeViewModel: SshHomeViewModel = viewModel()
                val appSessionManager = remember { AppSessionManager(this@SshActivity) }

                // Determine initial page: restore to terminal if there's an active session
                val hasActiveSession = remember {
                    launchSessionId != null || sshViewModel.sessionId.value != null
                            || sshViewModel.sshManager.getActiveSessions().isNotEmpty()
                }
                var page by remember {
                    mutableStateOf(if (hasActiveSession) "terminal" else "home")
                }

                // Auto-enter session: from intent, from ViewModel state, or from SshManager
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val targetSid = launchSessionId
                        ?: sshViewModel.sessionId.value
                        ?: sshViewModel.sshManager.getActiveSessions().firstOrNull()?.sessionId
                    if (targetSid != null) {
                        sshViewModel.enterSession(targetSid)
                        if (launchManualMode) sshViewModel.setManualMode(true)
                        page = "terminal"
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    when (page) {
                        "home" -> {
                            SshHomeScreen(
                                viewModel = sshHomeViewModel,
                                onBack = { finish() },
                                onEnterSession = { sessionId, manualMode ->
                                    val serverInfo = sshViewModel.sshManager.getSessionInfo(sessionId)
                                    if (serverInfo != null) {
                                        val servers = com.example.link_pi.data.SavedServerStorage(this@SshActivity).loadAll()
                                        val matched = servers.find { it.host == serverInfo.host && it.port == serverInfo.port }
                                        if (matched != null) {
                                            appSessionManager.suspend("builtin:ssh", AppType.BUILTIN_SSH, mapOf(
                                                "server_id" to matched.id,
                                                "manual_mode" to manualMode.toString()
                                            ))
                                        }
                                    }
                                    sshViewModel.enterSession(sessionId)
                                    if (manualMode) sshViewModel.setManualMode(true)
                                    page = "terminal"
                                }
                            )
                        }
                        "terminal" -> {
                            SshScreen(
                                viewModel = sshViewModel,
                                onBack = {
                                    sshViewModel.exitScreen()
                                    page = "home"
                                },
                                onDisconnect = {
                                    sshViewModel.disconnect()
                                    appSessionManager.destroy("builtin:ssh")
                                    page = "home"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTaskDescription("SSH 终端")
    }

    private fun applyTaskDescription(label: String) {
        try {
            val icon = createTaskIcon(label)
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label, icon))
        } catch (_: Exception) { /* some ROMs may throw */ }
    }

    private fun createTaskIcon(label: String): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6750A4.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 24f, 24f, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 72f
            textAlign = Paint.Align.CENTER
        }
        val fontMetrics = textPaint.fontMetrics
        val textY = (size - fontMetrics.top - fontMetrics.bottom) / 2f
        canvas.drawText(">_", size / 2f, textY, textPaint)
        return bitmap
    }
}
