package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.LangFilter
import com.selimdurmus.dictionary.ui.model.WordCard
import com.selimdurmus.dictionary.ui.model.WordDetail
import com.selimdurmus.dictionary.ui.model.buildCards
import com.selimdurmus.dictionary.ui.model.buildDetail
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A headword row in Saved / Recent / Top lists, with a short translation preview. */
data class WordRow(val word: String, val lang: String, val preview: String, val count: Int = 0)

data class SearchUiState(
    val query: String = "",
    val cards: List<WordCard> = emptyList(),
    /** Headword the results belong to when it isn't what was typed (lemma or spelling fix). */
    val shownFor: String? = null,
    val isLemma: Boolean = false,
    val loading: Boolean = false,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: DictionaryRepository) : ViewModel() {

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LangFilter.ALL)

    val state: StateFlow<SearchUiState> = combine(query, filter) { q, f -> q.trim() to f }
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { (q, f) ->
            if (q.isBlank()) SearchUiState()
            else {
                val r = repository.search(q, f)
                SearchUiState(q, buildCards(r.entries), r.suggestion, r.isLemma)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    /** Recent headwords for the empty search screen. */
    val recentWords: StateFlow<List<WordRow>> = repository.recentsStream(limit = 12)
        .map { list -> list.map { WordRow(it.word, it.lang, "") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // When typing settles and the top card is a real word, record it as a recent. Combining
        // query+state keeps them in lockstep: the debounce only fires after both stop changing.
        viewModelScope.launch {
            combine(query, state) { q, s -> q.trim() to s }
                .debounce(AUTO_RECORD_DEBOUNCE_MS)
                .filter { (q, s) -> q.length >= AUTO_RECORD_MIN_LEN && s.query == q && s.cards.isNotEmpty() }
                .collect { (_, s) ->
                    val top = s.cards.first()
                    repository.recordOpen(top.word, top.lang)
                }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 180L
        private const val AUTO_RECORD_DEBOUNCE_MS = 1_200L
        private const val AUTO_RECORD_MIN_LEN = 3
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WordDetailViewModel(private val repository: DictionaryRepository) : ViewModel() {

    private val target = MutableStateFlow<EntryTarget?>(null)

    val detail: StateFlow<WordDetail?> = target
        .mapLatest { t -> t?.let { buildDetail(it.word, it.lang, repository.entry(it.word, it.lang)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavorite: StateFlow<Boolean> = target
        .flatMapLatest { t -> t?.let { repository.isFavorite(it.word, it.lang) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun load(t: EntryTarget) {
        if (target.value == t) return
        target.value = t
        viewModelScope.launch { repository.recordOpen(t.word, t.lang) }
    }

    /** The screen passes the state it shows, so a tap always flips what the user sees. */
    fun setFavorite(t: EntryTarget, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(t.word, t.lang, favorite) }
    }
}

/** Shared "clear with undo" behavior: the list empties at once; the wipe commits after 5 s. */
private class PendingClear(private val scope: kotlinx.coroutines.CoroutineScope, private val commit: suspend () -> Unit) {
    val pending = MutableStateFlow(false)
    private var job: Job? = null

    fun start() {
        job?.cancel()
        pending.value = true
        job = scope.launch {
            delay(UNDO_WINDOW_MS)
            commit()
            pending.value = false
        }
    }

    fun undo() {
        job?.cancel()
        job = null
        pending.value = false
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun <T> Flow<List<T>>.withPreviews(
    repository: DictionaryRepository,
    pending: Flow<Boolean>,
    toRow: (T) -> WordRow,
): Flow<List<WordRow>> = combine(this, pending) { list, clearing -> if (clearing) emptyList() else list }
    .mapLatest { list -> list.map { toRow(it) }.map { it.copy(preview = repository.preview(it.word, it.lang)) } }

class HistoryViewModel(private val repository: DictionaryRepository) : ViewModel() {

    private val recentsClear = PendingClear(viewModelScope) { repository.clearRecents() }
    private val topClear = PendingClear(viewModelScope) { repository.clearTopWords() }

    val recents: StateFlow<List<WordRow>> = repository.recentsStream()
        .withPreviews(repository, recentsClear.pending) { WordRow(it.word, it.lang, "") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val top: StateFlow<List<WordRow>> = repository.topStream()
        .withPreviews(repository, topClear.pending) { WordRow(it.word, it.lang, "", it.count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(row: WordRow) {
        viewModelScope.launch { repository.deleteRecent(row.word, row.lang) }
    }

    fun clearRecents() = recentsClear.start()
    fun undoClearRecents() = recentsClear.undo()
    fun clearTop() = topClear.start()
    fun undoClearTop() = topClear.undo()
}

class SavedViewModel(private val repository: DictionaryRepository) : ViewModel() {

    val saved: StateFlow<List<WordRow>> = repository.favoritesStream()
        .withPreviews(repository, MutableStateFlow(false).asStateFlow()) { WordRow(it.word, it.lang, "") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(row: WordRow) {
        viewModelScope.launch { repository.setFavorite(row.word, row.lang, false) }
    }

    fun restore(row: WordRow) {
        viewModelScope.launch { repository.setFavorite(row.word, row.lang, true) }
    }
}

fun repositoryViewModelFactory(repository: DictionaryRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            SearchViewModel::class.java -> SearchViewModel(repository)
            WordDetailViewModel::class.java -> WordDetailViewModel(repository)
            HistoryViewModel::class.java -> HistoryViewModel(repository)
            SavedViewModel::class.java -> SavedViewModel(repository)
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
