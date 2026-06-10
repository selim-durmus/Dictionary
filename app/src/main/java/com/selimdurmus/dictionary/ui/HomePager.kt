package com.selimdurmus.dictionary.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.ui.theme.Background
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.OnMuted
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3
private val TAB_TITLES = listOf("Search", "Recents", "Top 50")

@Composable
fun HomePager(
    repository: DictionaryRepository,
    initialTarget: EntryTarget? = null,
    onTargetConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })
    var selected by remember { mutableStateOf<EntryTarget?>(null) }
    val open: (EntryTarget) -> Unit = { selected = it }

    // When a new intent arrives carrying a word to open (from the widget popup), open the
    // detail sheet for it. Notify the caller so it can clear its pending state.
    LaunchedEffect(initialTarget) {
        if (initialTarget != null) {
            selected = initialTarget
            onTargetConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> SearchScreen(
                    repository = repository,
                    isActive = pagerState.currentPage == 0,
                    onOpen = open,
                )
                1 -> RecentsScreen(repository = repository, onOpen = open)
                2 -> TopWordsScreen(repository = repository, onOpen = open)
            }
        }

        TabBar(
            pagerState = pagerState,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }

    selected?.let { target ->
        EntryDetailSheet(
            target = target,
            repository = repository,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun TabBar(pagerState: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        TAB_TITLES.forEachIndexed { index, title ->
            val active = pagerState.currentPage == index
            val color by animateColorAsState(
                targetValue = if (active) Gold else OnMuted,
                label = "tab-$index-color",
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                    .padding(vertical = 6.dp),
            )
        }
    }
}
