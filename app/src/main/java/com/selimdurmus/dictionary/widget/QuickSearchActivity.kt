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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.MainActivity
import com.selimdurmus.dictionary.TranslateApp
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Entry
import com.selimdurmus.dictionary.ui.SearchViewModel
import com.selimdurmus.dictionary.ui.repositoryViewModelFactory
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.DividerSubtle
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.OnHigh
import com.selimdurmus.dictionary.ui.theme.OnMuted
import com.selimdurmus.dictionary.ui.theme.TranslateTheme

class QuickSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TranslateApp).container.repository
        setContent {
            TranslateTheme {
                QuickSearchDialog(repository = repository, onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun QuickSearchDialog(repository: DictionaryRepository, onDismiss: () -> Unit) {
    val vm: SearchViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val backdropInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val openApp: () -> Unit = {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        onDismiss()
    }

    val openInApp: (Entry) -> Unit = { entry ->
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_WORD, entry.sourceWord)
                putExtra(MainActivity.EXTRA_OPEN_LANG, entry.sourceLang)
            }
        )
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = backdropInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(Background)
                // Swallow taps on the card so they don't reach the backdrop.
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                TextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = {
                        Text(
                            "Search",
                            color = OnMuted,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = OnHigh,
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        cursorColor = Gold,
                        focusedIndicatorColor = Gold,
                        unfocusedIndicatorColor = DividerSubtle,
                        focusedTextColor = OnHigh,
                        unfocusedTextColor = OnHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                results.suggestion?.let { word ->
                    val banner = buildAnnotatedString {
                        append("Showing results for ")
                        withStyle(SpanStyle(color = Gold)) { append(word) }
                    }
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = OnMuted,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                results.entries.firstOrNull()?.let { top ->
                    TopResult(
                        entry = top,
                        onClick = { openInApp(top) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = openApp) {
                        Text(
                            "Open app",
                            color = Gold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopResult(entry: Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "${entry.sourceWord} → ${entry.targetWord}",
            style = MaterialTheme.typography.titleMedium,
            color = OnHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(entry.pos, entry.category).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = OnMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
