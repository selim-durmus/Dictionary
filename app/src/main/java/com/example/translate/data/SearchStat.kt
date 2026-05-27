package com.example.translate.data

import androidx.room.Entity

@Entity(tableName = "search_stats", primaryKeys = ["word", "lang"])
data class SearchStat(
    val word: String,
    val lang: String,
    val count: Int,
    val lastSearchedAt: Long,
)
