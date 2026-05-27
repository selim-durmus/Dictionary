package com.example.translate.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDao {

    @Query("""
        INSERT INTO recents (word, lang, lastOpenedAt) VALUES (:word, :lang, :now)
        ON CONFLICT(word, lang) DO UPDATE SET lastOpenedAt = :now
    """)
    suspend fun upsert(word: String, lang: String, now: Long)

    @Query("SELECT * FROM recents ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observe(limit: Int = 200): Flow<List<Recent>>

    @Query("DELETE FROM recents WHERE word = :word AND lang = :lang")
    suspend fun delete(word: String, lang: String)

    @Query("DELETE FROM recents")
    suspend fun clear()
}
