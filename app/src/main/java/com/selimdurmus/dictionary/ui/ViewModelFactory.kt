package com.selimdurmus.dictionary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.selimdurmus.dictionary.data.DictionaryRepository

fun repositoryViewModelFactory(repository: DictionaryRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
            modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                SearchViewModel(repository) as T
            modelClass.isAssignableFrom(RecentsViewModel::class.java) ->
                RecentsViewModel(repository) as T
            modelClass.isAssignableFrom(TopWordsViewModel::class.java) ->
                TopWordsViewModel(repository) as T
            modelClass.isAssignableFrom(EntryDetailViewModel::class.java) ->
                EntryDetailViewModel(repository) as T
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
