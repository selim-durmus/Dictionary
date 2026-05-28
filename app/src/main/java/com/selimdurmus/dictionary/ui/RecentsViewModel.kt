package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Recent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecentsViewModel(private val repository: DictionaryRepository) : ViewModel() {

    val recents: StateFlow<List<Recent>> = repository.recentsStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(word: String, lang: String) {
        viewModelScope.launch { repository.deleteRecent(word, lang) }
    }
}
