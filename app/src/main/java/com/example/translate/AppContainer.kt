package com.example.translate

import android.content.Context
import com.example.translate.data.DictionaryDb
import com.example.translate.data.DictionaryRepository
import com.example.translate.data.EntryDao
import com.example.translate.data.UserDb

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
