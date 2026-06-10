package com.selimdurmus.dictionary

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.selimdurmus.dictionary.ui.EntryTarget
import com.selimdurmus.dictionary.ui.HomePager
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.TranslateTheme

class MainActivity : ComponentActivity() {

    // Observable so onNewIntent updates flow into the Compose tree on existing instances
    // (singleTask launch mode means the same activity gets new intents instead of being recreated).
    private var pendingTarget: EntryTarget? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as TranslateApp).container
        pendingTarget = targetFromIntent(intent)

        setContent {
            TranslateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Background),
                    color = Background,
                ) {
                    // First launch (or after an app update) copies the bundled DB out of assets;
                    // that's hundreds of MB, so do it off the main thread behind a loading screen
                    // instead of blocking onCreate (which would ANR).
                    var ready by remember { mutableStateOf(container.isReady()) }
                    LaunchedEffect(Unit) {
                        if (!ready) {
                            container.ensureReady()
                            ready = true
                        }
                    }

                    if (ready) {
                        HomePager(
                            repository = container.repository,
                            initialTarget = pendingTarget,
                            onTargetConsumed = { pendingTarget = null },
                        )
                    } else {
                        LoadingScreen()
                    }
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

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Dictionary", style = MaterialTheme.typography.titleLarge, color = Gold)
        CircularProgressIndicator(
            color = Gold,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
