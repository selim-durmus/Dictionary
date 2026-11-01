package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.selimdurmus.dictionary.R
import com.selimdurmus.dictionary.ui.components.EmptyState
import com.selimdurmus.dictionary.ui.components.LangBadge
import com.selimdurmus.dictionary.ui.components.SourceTag
import com.selimdurmus.dictionary.ui.components.copyText
import com.selimdurmus.dictionary.ui.model.PosSection
import com.selimdurmus.dictionary.ui.model.SenseGroup
import com.selimdurmus.dictionary.ui.model.WordDetail
import com.selimdurmus.dictionary.ui.model.posLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailScreen(
    target: EntryTarget,
    vm: WordDetailViewModel,
    tts: TtsController,
    onBack: () -> Unit,
) {
    LaunchedEffect(target) { vm.load(target) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val current = detail?.takeIf { it.word == target.word && it.lang == target.lang }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(target.word, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(10.dp))
                        LangBadge(target.lang)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { tts.speak(target.word, target.lang) }) {
                        Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Pronounce")
                    }
                    IconButton(onClick = {
                        current?.let { d -> copyText(clipboard, context, copyAll(d)) }
                    }) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy")
                    }
                    IconButton(onClick = { vm.setFavorite(target, !favorite) }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (favorite) "Remove from saved" else "Save",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            current == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.translations.isEmpty() && current.definitions.isEmpty() && current.notes.isEmpty() ->
                EmptyState(
                    icon = painterResource(R.drawable.ic_search),
                    title = "Nothing found",
                    body = "This word has no entries yet.",
                    modifier = Modifier.padding(padding),
                )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current.notes.forEach { note ->
                    item {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                sections(current.translations, title = if (current.lang == "en") "Turkish" else "English", word = current.word)
                sections(current.definitions, title = "Definitions", word = current.word)
            }
        }
    }
}

private fun LazyListScope.sections(sections: List<PosSection>, title: String, word: String) {
    if (sections.isEmpty()) return
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
    }
    sections.forEach { section ->
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    posLabel(section.pos)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    section.senses.forEachIndexed { i, sense ->
                        if (i > 0) HorizontalDivider(
                            modifier = Modifier.padding(start = 44.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SenseRow(i + 1, sense)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SenseRow(number: Int, sense: SenseGroup) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { copyText(clipboard, context, sense.copyText) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = sense.terms.joinToString(", "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            sense.definition?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val showCategory = sense.category != "General"
            if (showCategory || sense.sourceLabel != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showCategory) SourceTag(sense.category)
                    sense.sourceLabel?.let { SourceTag(it) }
                }
            }
        }
    }
}

private fun copyAll(d: WordDetail): String = buildString {
    append(d.word)
    d.translations.forEach { s ->
        append("\n")
        posLabel(s.pos)?.let { append("$it: ") }
        append(s.senses.flatMap { it.terms }.distinct().joinToString(", "))
    }
}
