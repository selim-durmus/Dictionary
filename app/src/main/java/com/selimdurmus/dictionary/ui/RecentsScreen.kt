package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Recent
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.OnHigh
import com.selimdurmus.dictionary.ui.theme.OnMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    repository: DictionaryRepository,
    onOpen: (EntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: RecentsViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val recents by vm.recents.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
        Text(
            text = "Recents",
            style = MaterialTheme.typography.labelMedium,
            color = OnMuted,
            modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
        )

        if (recents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recent lookups", style = MaterialTheme.typography.bodyMedium, color = OnMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(recents, key = { "${it.lang}:${it.word}" }) { recent ->
                    SwipeableRecentRow(
                        recent = recent,
                        onOpen = { onOpen(EntryTarget(recent.word, recent.lang)) },
                        onDelete = { vm.delete(recent.word, recent.lang) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRecentRow(
    recent: Recent,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(); true
            } else false
        },
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF3A1414))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE57373),
                )
            }
        },
    ) {
        RecentRow(recent = recent, onClick = onOpen)
    }
}

@Composable
private fun RecentRow(recent: Recent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = recent.word,
            style = MaterialTheme.typography.titleMedium,
            color = OnHigh,
        )
        Text(
            text = recent.lang.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = OnMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
