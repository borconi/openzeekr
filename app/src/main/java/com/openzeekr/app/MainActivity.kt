package com.openzeekr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.openzeekr.app.ui.AppRoot
import com.openzeekr.app.ui.theme.OpenZeekrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as App).deps
        setContent {
            val cfg by deps.config.config.collectAsState()
            OpenZeekrTheme(signature = cfg.uiTheme == "signature") {
                AppRoot(deps)
            }
        }
    }
}
