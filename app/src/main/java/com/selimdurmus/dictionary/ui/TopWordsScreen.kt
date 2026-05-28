package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.SearchStat
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.OnHigh
import com.selimdurmus.dictionary.ui.theme.OnMuted

@Composable
fun TopWordsScreen(
    repository: DictionaryRepository,
    onOpen: (EntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TopWordsViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val top by vm.top.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
        Text(
            text = "Top 50",
            style = MaterialTheme.typography.labelMedium,
            color = OnMuted,
            modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
        )

        if (top.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Look up a few words to fill this list", style = MaterialTheme.typography.bodyMedium, color = OnMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(top, key = { _, stat -> "${stat.lang}:${stat.word}" }) { index, stat ->
                    TopRow(rank = index + 1, stat = stat, onClick = { onOpen(EntryTarget(stat.word, stat.lang)) })
                }
            }
        }
    }
}

@Composable
private fun TopRow(rank: Int, stat: SearchStat, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge,
            color = Gold,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stat.word,
                style = MaterialTheme.typography.titleMedium,
                color = OnHigh,
            )
            Text(
                text = stat.lang.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = OnMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "×${stat.count}",
            style = MaterialTheme.typography.labelLarge,
            color = OnMuted,
        )
    }
}
