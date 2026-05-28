package com.selimdurmus.dictionary.data

import kotlinx.coroutines.flow.Flow

class DictionaryRepository(
    private val entries: EntryDao,
    private val recents: RecentDao,
    private val stats: StatsDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun search(
        query: String,
        filter: LangFilter = LangFilter.ALL,
    ): SearchResults = entries.search(query, filter = filter)

    suspend fun entry(word: String, lang: String): List<Entry> = entries.entriesFor(word, lang)

    /** Record that the user opened an entry — drives both the Top 50 and Recents pages. */
    suspend fun recordOpen(word: String, lang: String) {
        val now = clock()
        stats.increment(word, lang, now)
        recents.upsert(word, lang, now)
    }

    fun recentsStream(limit: Int = 200): Flow<List<Recent>> = recents.observe(limit)

    fun topStream(limit: Int = 50): Flow<List<SearchStat>> = stats.observeTop(limit)

    suspend fun deleteRecent(word: String, lang: String) = recents.delete(word, lang)
}
