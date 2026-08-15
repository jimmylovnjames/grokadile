package com.grokadile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.grokadile.service.ShareIntake
import com.grokadile.ui.GrokadileRoot
import com.grokadile.ui.theme.GrokadileTheme
import com.grokadile.voice.VoiceAssistant
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var shareIntake: ShareIntake
    @Inject lateinit var voiceAssistant: VoiceAssistant

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrokadileTheme {
                GrokadileRoot()
            }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        lifecycleScope.launch {
            val result = shareIntake.ingest(text, subject)
            voiceAssistant.postSystem(result.message)
        }
        // Consume so rotation / recreation does not re-ingest.
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_TEXT)
    }
}
