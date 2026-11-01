package com.selimdurmus.dictionary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selimdurmus.dictionary.R
import com.selimdurmus.dictionary.data.DictionaryRepository

private enum class Tab(val label: String) { Search("Search"), Saved("Saved"), History("History") }

/** Root of the app: bottom navigation between Search / Saved / History, and a word detail page. */
@Composable
fun DictionaryApp(
    repository: DictionaryRepository,
    initialTarget: EntryTarget?,
    onTargetConsumed: () -> Unit,
) {
    val factory = remember(repository) { repositoryViewModelFactory(repository) }
    val searchVm: SearchViewModel = viewModel(factory = factory)
    val detailVm: WordDetailViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)
    val savedVm: SavedViewModel = viewModel(factory = factory)
    val tts = rememberTts()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableIntStateOf(Tab.Search.ordinal) }
    val stack = remember { mutableStateListOf<EntryTarget>() }

    LaunchedEffect(initialTarget) {
        if (initialTarget != null) {
            stack.clear()
            stack.add(initialTarget)
            onTargetConsumed()
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && tab != Tab.Search.ordinal) { tab = Tab.Search.ordinal }

    val open: (EntryTarget) -> Unit = { stack.add(it) }

    AnimatedContent(
        targetState = stack.lastOrNull(),
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
        },
        label = "page",
    ) { target ->
        if (target != null) {
            WordDetailScreen(
                target = target,
                vm = detailVm,
                tts = tts,
                onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
            )
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            val selected = tab == t.ordinal
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = t.ordinal },
                                label = { Text(t.label) },
                                icon = {
                                    when (t) {
                                        Tab.Search -> Icon(Icons.Filled.Search, contentDescription = null)
                                        Tab.Saved -> Icon(
                                            if (selected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                            contentDescription = null,
                                        )
                                        Tab.History -> Icon(painterResource(R.drawable.ic_history), contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (Tab.entries[tab]) {
                        Tab.Search -> SearchScreen(searchVm, tts, isActive = stack.isEmpty(), onOpen = open)
                        Tab.Saved -> SavedScreen(savedVm, snackbar, onOpen = open)
                        Tab.History -> HistoryScreen(historyVm, snackbar, onOpen = open)
                    }
                }
            }
        }
    }
}
