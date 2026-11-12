package app.slate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.slate.data.Settings
import app.slate.ui.SlateRoot
import app.slate.ui.theme.SlateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = SlateApp.get(this)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
            SlateTheme(settings.theme) { SlateRoot(settings) }
        }
    }
}