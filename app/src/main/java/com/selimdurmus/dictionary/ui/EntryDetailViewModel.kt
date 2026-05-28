package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EntryDetailViewModel(private val repository: DictionaryRepository) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Loaded(val entries: List<Entry>) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(target: EntryTarget) {
        _state.value = State.Loading
        viewModelScope.launch {
            repository.recordOpen(target.word, target.lang)
            _state.value = State.Loaded(repository.entry(target.word, target.lang))
        }
    }
}
