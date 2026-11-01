package com.selimdurmus.dictionary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A headword the user starred. */
@Entity(tableName = "favorites", primaryKeys = ["word", "lang"])
data class Favorite(
    val word: String,
    val lang: String,
    val addedAt: Long,
)

@Dao
interface FavoriteDao {

    @Query("INSERT OR REPLACE INTO favorites (word, lang, addedAt) VALUES (:word, :lang, :now)")
    suspend fun add(word: String, lang: String, now: Long)

    @Query("DELETE FROM favorites WHERE word = :word AND lang = :lang")
    suspend fun remove(word: String, lang: String)

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observe(): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE word = :word AND lang = :lang)")
    fun observeIsFavorite(word: String, lang: String): Flow<Boolean>
}
