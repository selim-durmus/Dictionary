package com.selimdurmus.dictionary

import android.content.Context
import com.selimdurmus.dictionary.data.DictionaryDb
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.EntryDao
import com.selimdurmus.dictionary.data.UserDb

class AppContainer(applicationContext: Context) {
    private val dictionary by lazy { DictionaryDb.open(applicationContext) }
    private val userDb by lazy { UserDb.build(applicationContext) }

    val repository: DictionaryRepository by lazy {
        DictionaryRepository(
            entries = EntryDao(dictionary),
            recents = userDb.recents(),
            stats = userDb.stats(),
        )
    }
}
