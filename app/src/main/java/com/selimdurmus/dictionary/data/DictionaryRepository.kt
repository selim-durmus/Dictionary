package com.selimdurmus.dictionary.data

import kotlinx.coroutines.flow.Flow

class DictionaryRepository(
    private val entries: EntryDao,
    private val recents: RecentDao,
    private val stats: StatsDao,
    private val favorites: FavoriteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun search(
        query: String,
        filter: LangFilter = LangFilter.ALL,
    ): SearchResults = entries.search(query, filter = filter)

    suspend fun entry(word: String, lang: String): List<Entry> = entries.entriesFor(word, lang)

    /** First few distinct translations of a headword, for one-line previews in lists. */
    suspend fun preview(word: String, lang: String, max: Int = 4): String =
        entries.entriesFor(word, lang)
            .filter { it.targetLang != it.sourceLang && it.category != INFLECTION_CATEGORY }
            .map { it.targetWord }
            .distinctBy { it.lowercase() }
            .take(max)
            .joinToString(", ")

    /** Record that the user opened an entry — drives both the Top 50 and Recents pages. */
    suspend fun recordOpen(word: String, lang: String) {
        val now = clock()
        stats.increment(word, lang, now)
        recents.upsert(word, lang, now)
    }

    fun recentsStream(limit: Int = 200): Flow<List<Recent>> = recents.observe(limit)

    fun topStream(limit: Int = 50): Flow<List<SearchStat>> = stats.observeTop(limit)

    suspend fun deleteRecent(word: String, lang: String) = recents.delete(word, lang)

    suspend fun clearRecents() = recents.clear()

    suspend fun clearTopWords() = stats.clear()

    fun favoritesStream(): Flow<List<Favorite>> = favorites.observe()

    fun isFavorite(word: String, lang: String): Flow<Boolean> = favorites.observeIsFavorite(word, lang)

    suspend fun setFavorite(word: String, lang: String, favorite: Boolean) {
        if (favorite) favorites.add(word, lang, clock()) else favorites.remove(word, lang)
    }

    companion object {
        const val INFLECTION_CATEGORY = "Inflection"
    }
}
