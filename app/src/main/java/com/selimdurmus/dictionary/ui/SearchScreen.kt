package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Entry
import com.selimdurmus.dictionary.data.LangFilter
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.DividerSubtle
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.OnHigh
import com.selimdurmus.dictionary.ui.theme.OnMuted

@Composable
fun SearchScreen(
    repository: DictionaryRepository,
    isActive: Boolean,
    onOpen: (EntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SearchViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Local field state holds text + cursor selection together. We mirror the VM's text into
    // it and, when the tab becomes active, force selection to the end so refocusing doesn't
    // land the caret at position 0 of the existing query.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(query, TextRange(query.length)))
    }
    LaunchedEffect(query) {
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(query, TextRange(query.length))
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
        Text(
            text = "Dictionary",
            style = MaterialTheme.typography.labelMedium,
            color = OnMuted,
            modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
        )

        TextField(
            value = fieldValue,
            onValueChange = { new ->
                fieldValue = new
                if (new.text != query) vm.query.value = new.text
            },
            placeholder = { Text("Search", color = OnMuted, style = MaterialTheme.typography.titleMedium) },
            trailingIcon = {
                if (fieldValue.text.isNotEmpty()) {
                    IconButton(onClick = {
                        fieldValue = TextFieldValue("", TextRange(0))
                        vm.query.value = ""
                        focusRequester.requestFocus() // keep the field focused + keyboard up
                    }) {
                        Text("✕", color = OnMuted, style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = OnHigh, fontSize = MaterialTheme.typography.titleMedium.fontSize),
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
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )

        LangFilterChips(
            current = filter,
            onSelect = { vm.filter.value = it },
            modifier = Modifier.padding(top = 12.dp),
        )

        results.suggestion?.let { word ->
            CorrectionBanner(suggested = word)
        }

        if (query.isNotBlank() && results.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No results for “${query.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results.entries, key = { it.id }) { entry ->
                    ResultRow(entry, onClick = { onOpen(EntryTarget(entry.sourceWord, entry.sourceLang)) })
                }
            }
        }
    }
}

@Composable
private fun LangFilterChips(
    current: LangFilter,
    onSelect: (LangFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip("All", LangFilter.ALL, current, onSelect)
        FilterChip("EN → TR", LangFilter.EN_TR, current, onSelect)
        FilterChip("TR → EN", LangFilter.TR_EN, current, onSelect)
    }
}

@Composable
private fun FilterChip(
    label: String,
    value: LangFilter,
    current: LangFilter,
    onSelect: (LangFilter) -> Unit,
) {
    val active = value == current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) Background else Gold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Gold else Color.Transparent)
            .border(1.dp, Gold, RoundedCornerShape(50))
            .clickable { onSelect(value) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CorrectionBanner(suggested: String) {
    val text = buildAnnotatedString {
        append("Showing results for ")
        withStyle(SpanStyle(color = Gold)) { append(suggested) }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OnMuted,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(entry: Entry, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { copyEntry(clipboard, context, entry) },
            )
            .padding(vertical = 6.dp),
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
