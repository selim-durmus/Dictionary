package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.selimdurmus.dictionary.R
import com.selimdurmus.dictionary.data.LangFilter
import com.selimdurmus.dictionary.ui.components.EmptyState
import com.selimdurmus.dictionary.ui.components.LangBadge
import com.selimdurmus.dictionary.ui.components.WordCardView

private val TURKISH_LETTERS = listOf("ç", "ğ", "ı", "ö", "ş", "ü")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    tts: TtsController,
    isActive: Boolean,
    onOpen: (EntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = vm.query.value
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val recents by vm.recentWords.collectAsStateWithLifecycle()

    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query, TextRange(query.length)))
    }
    // Replace the text only on explicit picks (a recent word); never re-sync from the debounced
    // query while typing, which raced the keyboard and dropped letters.
    val setText: (String) -> Unit = { text ->
        field = TextFieldValue(text, TextRange(text.length))
        vm.query.value = text
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(isActive) {
        if (isActive) focusRequester.requestFocus() else { keyboard?.hide(); focusManager.clearFocus() }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.query) { listState.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
            TextField(
                value = field,
                onValueChange = { field = it; vm.query.value = it.text },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                placeholder = { Text("Search English or Turkish") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (field.text.isNotEmpty()) {
                        IconButton(onClick = { setText("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TURKISH_LETTERS.forEach { letter ->
                    Surface(
                        onClick = {
                            val t = field.text
                            val start = field.selection.min.coerceIn(0, t.length)
                            val end = field.selection.max.coerceIn(0, t.length)
                            val new = t.substring(0, start) + letter + t.substring(end)
                            field = TextFieldValue(new, TextRange(start + letter.length))
                            vm.query.value = new
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(LangFilter.ALL to "All", LangFilter.EN_TR to "EN → TR", LangFilter.TR_EN to "TR → EN")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { vm.filter.value = value },
                            label = { Text(label) },
                        )
                    }
            }
        }

        when {
            field.text.isBlank() -> RecentsHint(recents, onPick = setText)
            state.query.isNotBlank() && state.cards.isEmpty() && state.query == field.text.trim() -> EmptyState(
                icon = painterResource(R.drawable.ic_search),
                title = "No results for “${state.query}”",
                body = "Check the spelling, or try the base form of the word.",
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.shownFor?.let { word ->
                    item(key = "banner") {
                        Text(
                            text = if (state.isLemma) "“${state.query}” is a form of $word"
                            else "Showing results for $word",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                items(state.cards, key = { it.lang + ":" + it.word }) { card ->
                    WordCardView(
                        card = card,
                        onClick = { onOpen(EntryTarget(card.word, card.lang)) },
                        onSpeak = { tts.speak(card.word, card.lang) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentsHint(recents: List<WordRow>, onPick: (String) -> Unit) {
    if (recents.isEmpty()) {
        EmptyState(
            icon = painterResource(R.drawable.ic_translate),
            title = "Look up any word",
            body = "English or Turkish, in any form. Gidiyorum finds gitmek, went finds go.",
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recents.forEach { row ->
                AssistChip(
                    onClick = { onPick(row.word) },
                    label = { Text(row.word) },
                    trailingIcon = { LangBadge(row.lang) },
                )
            }
        }
    }
}
