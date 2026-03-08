package com.example.link_pi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.link_pi.ui.navigation.LinkPiApp
import com.example.link_pi.ui.theme.LinkpiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinkpiTheme {
                LinkPiApp()
            }
        }
    }
}