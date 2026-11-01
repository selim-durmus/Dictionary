package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.selimdurmus.dictionary.R
import com.selimdurmus.dictionary.ui.components.EmptyState
import com.selimdurmus.dictionary.ui.components.LangBadge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(vm: SavedViewModel, snackbar: SnackbarHostState, onOpen: (EntryTarget) -> Unit) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Saved") })
        if (saved.isEmpty()) {
            EmptyState(
                icon = rememberVectorPainterFavorite(),
                title = "No saved words yet",
                body = "Tap the heart on a word to keep it here.",
            )
        } else {
            WordList(
                rows = saved,
                onOpen = onOpen,
                onSwipe = { row ->
                    vm.remove(row)
                    scope.launch {
                        val r = snackbar.showSnackbar("Removed “${row.word}”", "Undo", duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) vm.restore(row)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, snackbar: SnackbarHostState, onOpen: (EntryTarget) -> Unit) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val top by vm.top.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val rows = if (tab == 0) recents else top

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History") },
            actions = {
                if (rows.isNotEmpty()) {
                    TextButton(onClick = {
                        val recentTab = tab == 0
                        if (recentTab) vm.clearRecents() else vm.clearTop()
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                if (recentTab) "Recent searches cleared" else "Top 50 cleared",
                                "Undo",
                                duration = SnackbarDuration.Short,
                            )
                            if (r == SnackbarResult.ActionPerformed) {
                                if (recentTab) vm.undoClearRecents() else vm.undoClearTop()
                            }
                        }
                    }) { Text("Clear") }
                }
            },
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            listOf("Recent", "Top 50").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                ) { Text(label) }
            }
        }
        if (rows.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_history),
                title = if (tab == 0) "No recent searches" else "No searches yet",
                body = if (tab == 0) "Words you look up show up here." else "Your most-searched words will rank here.",
            )
        } else {
            WordList(
                rows = rows,
                onOpen = onOpen,
                ranked = tab == 1,
                onSwipe = if (tab == 0) vm::delete else null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordList(
    rows: List<WordRow>,
    onOpen: (EntryTarget) -> Unit,
    ranked: Boolean = false,
    onSwipe: ((WordRow) -> Unit)? = null,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.lang + ":" + r.word }) { index, row ->
            val item: @Composable () -> Unit = {
                ListItem(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpen(EntryTarget(row.word, row.lang)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = if (ranked) {
                        {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(28.dp),
                            )
                        }
                    } else null,
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.word, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            LangBadge(row.lang)
                        }
                    },
                    supportingContent = if (row.preview.isNotBlank()) {
                        { Text(row.preview, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    trailingContent = if (ranked) {
                        { Text("${row.count}×", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                )
            }
            if (onSwipe == null) item() else {
                val state = rememberSwipeToDismissBoxState(confirmValueChange = {
                    if (it != SwipeToDismissBoxValue.Settled) onSwipe(row)
                    it != SwipeToDismissBoxValue.Settled
                })
                SwipeToDismissBox(
                    state = state,
                    backgroundContent = {
                        Row(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    enableDismissFromStartToEnd = false,
                ) { item() }
            }
        }
    }
}

@Composable
private fun rememberVectorPainterFavorite() =
    androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.FavoriteBorder)
