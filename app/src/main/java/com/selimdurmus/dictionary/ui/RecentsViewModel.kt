package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.Recent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecentsViewModel(private val repository: DictionaryRepository) : ViewModel() {

    // When non-null, the clock-time (ms) at which the user tapped "Clear". While set, the displayed
    // list is force-emptied and a 5s job is running that will eventually wipe the DB. Undo cancels
    // the job and nulls this back out before the wipe happens.
    private val _pendingClearAt = MutableStateFlow<Long?>(null)
    val pendingClearAt: StateFlow<Long?> = _pendingClearAt.asStateFlow()

    val recents: StateFlow<List<Recent>> = combine(
        repository.recentsStream(),
        _pendingClearAt,
    ) { list, clearing -> if (clearing != null) emptyList() else list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var clearJob: Job? = null

    fun delete(word: String, lang: String) {
        viewModelScope.launch { repository.deleteRecent(word, lang) }
    }

    fun clearAll() {
        clearJob?.cancel()
        val stamp = System.currentTimeMillis()
        _pendingClearAt.value = stamp
        clearJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            // Only commit the wipe if no one canceled (and no second clear came in to replace us).
            if (_pendingClearAt.value == stamp) {
                repository.clearRecents()
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
