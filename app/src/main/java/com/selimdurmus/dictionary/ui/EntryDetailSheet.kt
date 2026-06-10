package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.selimdurmus.dictionary.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Entry
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.DividerSubtle
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.GoldDim
import com.selimdurmus.dictionary.ui.theme.OnHigh
import com.selimdurmus.dictionary.ui.theme.OnMedium
import com.selimdurmus.dictionary.ui.theme.OnMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailSheet(
    target: EntryTarget,
    repository: DictionaryRepository,
    onDismiss: () -> Unit,
) {
    val vm: EntryDetailViewModel = viewModel(factory = repositoryViewModelFactory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tts = rememberTts()

    LaunchedEffect(target) { vm.load(target) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
        contentColor = OnHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.word,
                    style = MaterialTheme.typography.titleLarge,
                    color = Gold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { tts.speak(target.word, target.lang) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up),
                        contentDescription = "Pronounce",
                        tint = Gold,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = target.lang.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = OnMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            when (val s = state) {
                EntryDetailViewModel.State.Loading -> Spacer(Modifier.height(120.dp))
                is EntryDetailViewModel.State.Loaded -> if (s.entries.isEmpty()) {
                    Text(
                        text = "No entry",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    SenseList(s.entries)
                }
            }
        }
    }
}

@Composable
private fun SenseList(entries: List<Entry>) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            SenseRow(entry)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SenseRow(entry: Entry) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { copyEntry(clipboard, context, entry) },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = entry.targetWord,
                style = MaterialTheme.typography.titleMedium,
                color = OnHigh,
                modifier = Modifier.weight(1f),
            )
            CategoryChip(entry.category)
        }
        val meta = listOfNotNull(entry.pos, entry.targetLang.uppercase()).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = OnMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        entry.definition?.takeIf { it.isNotBlank() }?.let { def ->
            Text(
                text = def,
                style = MaterialTheme.typography.bodyMedium,
                color = OnMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CategoryChip(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.labelSmall,
        color = GoldDim,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DividerSubtle)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
