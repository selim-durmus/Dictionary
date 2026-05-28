package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.LangFilter
import com.selimdurmus.dictionary.data.SearchResults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: DictionaryRepository) : ViewModel() {

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LangFilter.ALL)

    val results: StateFlow<SearchResults> = combine(query, filter) { q, f -> q to f }
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { (q, f) ->
            if (q.isBlank()) SearchResults(emptyList()) else repository.search(q, f)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults(emptyList()))

    init {
        // When typing settles (no changes for AUTO_RECORD_DEBOUNCE_MS) and the top result is
        // a real word, record it as a recent. Combining query+results in one flow keeps them in
        // lockstep: the debounce only fires after both have stopped changing.
        viewModelScope.launch {
            combine(query, results) { q, r -> q.trim() to r }
                .debounce(AUTO_RECORD_DEBOUNCE_MS)
                .filter { (q, r) -> q.length >= AUTO_RECORD_MIN_LEN && r.entries.isNotEmpty() }
                .collect { (_, r) ->
                    val top = r.entries.first()
                    repository.recordOpen(top.sourceWord, top.sourceLang)
                }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 200L
        private const val AUTO_RECORD_DEBOUNCE_MS = 1_000L
        private const val AUTO_RECORD_MIN_LEN = 3
    }
}
