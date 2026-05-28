package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.SearchStat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TopWordsViewModel(private val repository: DictionaryRepository) : ViewModel() {

    private val _pendingClearAt = MutableStateFlow<Long?>(null)
    val pendingClearAt: StateFlow<Long?> = _pendingClearAt.asStateFlow()

    val top: StateFlow<List<SearchStat>> = combine(
        repository.topStream(),
        _pendingClearAt,
    ) { list, clearing -> if (clearing != null) emptyList() else list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var clearJob: Job? = null

    fun clearAll() {
        clearJob?.cancel()
        val stamp = System.currentTimeMillis()
        _pendingClearAt.value = stamp
        clearJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            if (_pendingClearAt.value == stamp) {
                repository.clearTopWords()
                _pendingClearAt.value = null
            }
        }
    }

    fun undoClear() {
        clearJob?.cancel()
        clearJob = null
        _pendingClearAt.value = null
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
