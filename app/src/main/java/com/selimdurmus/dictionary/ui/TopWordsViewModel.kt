package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.SearchStat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TopWordsViewModel(repository: DictionaryRepository) : ViewModel() {

    val top: StateFlow<List<SearchStat>> = repository.topStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
