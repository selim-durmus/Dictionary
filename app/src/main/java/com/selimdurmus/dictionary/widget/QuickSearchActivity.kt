package com.selimdurmus.dictionary.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.MainActivity
import com.selimdurmus.dictionary.TranslateApp
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.ui.SearchViewModel
import com.selimdurmus.dictionary.ui.components.WordCardView
import com.selimdurmus.dictionary.ui.repositoryViewModelFactory
import com.selimdurmus.dictionary.ui.rememberTts
import com.selimdurmus.dictionary.ui.theme.DictionaryTheme

/** Translucent popup launched from the home-screen widget: search, see the top match, jump in. */
class QuickSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TranslateApp).container
        setContent {
            DictionaryTheme {
                // Don't touch the repository until the bundled DB is copied out of assets —
                // a first-ever launch via the widget could otherwise hit a missing file.
                var ready by remember { mutableStateOf(container.isReady()) }
                LaunchedEffect(Unit) {
                    if (!ready) {
                        container.ensureReady()
                        ready = true
                    }
                }
                if (ready) QuickSearchDialog(repository = container.repository, onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun QuickSearchDialog(repository: DictionaryRepository, onDismiss: () -> Unit) {
    val vm: SearchViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tts = rememberTts()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun launch(word: String?, lang: String?) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (word != null && lang != null) {
                    putExtra(MainActivity.EXTRA_OPEN_WORD, word)
                    putExtra(MainActivity.EXTRA_OPEN_LANG, lang)
                }
            }
        )
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                TextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text("Search English or Turkish") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                state.shownFor?.let { word ->
                    Text(
                        text = if (state.isLemma) "Form of $word" else "Showing results for $word",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                    )
                }
                state.cards.firstOrNull()?.let { card ->
                    WordCardView(
                        card = card,
                        onClick = { launch(card.word, card.lang) },
                        onSpeak = { tts.speak(card.word, card.lang) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { launch(null, null) }) { Text("Open app") }
                }
            }
        }
    }
}
