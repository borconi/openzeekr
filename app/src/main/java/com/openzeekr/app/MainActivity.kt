package com.openzeekr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.openzeekr.app.ui.AppRoot
import com.openzeekr.app.ui.theme.Brand
import com.openzeekr.app.ui.theme.OpenZeekrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as App).deps
        setContent {
            val cfg by deps.config.config.collectAsState()
            OpenZeekrTheme(cfg.theme) {
                // The system bars are transparent and the app draws behind them, so their icons must suit the
                // APP's ground (which may differ from the phone's own light/dark setting), and the system must
                // not lay its own contrast scrim over our bottom bar.
                val dark = Brand.isDark
                DisposableEffect(dark) {
                    val clear = android.graphics.Color.TRANSPARENT
                    val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                    // "auto" keeps the system's contrast protection for 3-button navigation, so its buttons stay
                    // readable over whatever is behind them; with gesture navigation the bar is fully clear.
                    val nav = SystemBarStyle.auto(
                        android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF), android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B),
                    ) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = nav)
                    onDispose {}
                }
                AppRoot(deps)
            }
        }
    }
}
