package com.example.translate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.translate.data.DictionaryRepository
import com.example.translate.ui.theme.Background
import com.example.translate.ui.theme.Gold
import com.example.translate.ui.theme.GoldDim

private const val PAGE_COUNT = 3

@Composable
fun HomePager(repository: DictionaryRepository, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })

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
                0 -> SearchScreen(repository = repository)
                1 -> RecentsScreen(repository = repository)
                2 -> TopWordsScreen(repository = repository)
            }
        }

        DotIndicator(
            current = pagerState.currentPage,
            total = PAGE_COUNT,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun DotIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { idx ->
            val active = idx == current
            val fill by animateColorAsState(
                targetValue = if (active) Gold else Background,
                label = "dot-$idx-fill",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .border(width = 1.dp, color = if (active) Gold else GoldDim, shape = CircleShape)
                    .background(fill, CircleShape),
            )
        }
    }
}
