package com.selimdurmus.dictionary

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.selimdurmus.dictionary.ui.EntryTarget
import com.selimdurmus.dictionary.ui.HomePager
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.TranslateTheme

class MainActivity : ComponentActivity() {

    // Observable so onNewIntent updates flow into the Compose tree on existing instances
    // (singleTask launch mode means the same activity gets new intents instead of being recreated).
    private var pendingTarget: EntryTarget? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as TranslateApp).container.repository
        pendingTarget = targetFromIntent(intent)

        setContent {
            TranslateTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background),
                    color = Background,
                ) {
                    HomePager(
                        repository = repository,
                        initialTarget = pendingTarget,
                        onTargetConsumed = { pendingTarget = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTarget = targetFromIntent(intent)
    }

    private fun targetFromIntent(intent: Intent?): EntryTarget? {
        if (intent == null) return null
        val word = intent.getStringExtra(EXTRA_OPEN_WORD) ?: return null
        val lang = intent.getStringExtra(EXTRA_OPEN_LANG) ?: return null
        return EntryTarget(word, lang)
    }

    companion object {
        const val EXTRA_OPEN_WORD = "com.selimdurmus.dictionary.OPEN_WORD"
        const val EXTRA_OPEN_LANG = "com.selimdurmus.dictionary.OPEN_LANG"
    }
}
