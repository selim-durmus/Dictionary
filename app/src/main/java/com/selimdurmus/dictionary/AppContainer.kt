package com.selimdurmus.dictionary

import android.content.Context
import com.selimdurmus.dictionary.data.DictionaryDb
import com.selimdurmus.dictionary.data.DictionaryRepository
import com.selimdurmus.dictionary.data.EntryDao
import com.selimdurmus.dictionary.data.UserDb

class AppContainer(private val appContext: Context) {
    // `dictionary` opens the local copy — only touch it after ensureReady() has run, otherwise
    // open() would race the first-run copy. `repository` is lazy for the same reason.
    private val dictionary by lazy { DictionaryDb.open(appContext) }
    private val userDb by lazy { UserDb.build(appContext) }

    val repository: DictionaryRepository by lazy {
        DictionaryRepository(
            entries = EntryDao(dictionary),
            recents = userDb.recents(),
            stats = userDb.stats(),
        )
    }

    /** Cheap synchronous check — true if the bundled DB is already copied + current. */
    fun isReady(): Boolean = DictionaryDb.isPrepared(appContext)

    /** Copy the bundled DB out of assets if needed (off the main thread). Idempotent. */
    suspend fun ensureReady() = DictionaryDb.prepare(appContext)
}
