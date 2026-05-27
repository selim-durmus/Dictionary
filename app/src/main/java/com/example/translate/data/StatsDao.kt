package com.example.translate.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {

    @Query("""
        INSERT INTO search_stats (word, lang, count, lastSearchedAt)
        VALUES (:word, :lang, 1, :now)
        ON CONFLICT(word, lang) DO UPDATE SET
            count = count + 1,
            lastSearchedAt = :now
    """)
    suspend fun increment(word: String, lang: String, now: Long)

    @Query("SELECT * FROM search_stats ORDER BY count DESC, lastSearchedAt DESC LIMIT :limit")
    fun observeTop(limit: Int = 50): Flow<List<SearchStat>>

    @Query("DELETE FROM search_stats")
    suspend fun clear()
}
