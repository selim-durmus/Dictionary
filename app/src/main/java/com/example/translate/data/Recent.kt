package com.example.translate.data

import androidx.room.Entity

@Entity(tableName = "recents", primaryKeys = ["word", "lang"])
data class Recent(
    val word: String,
    val lang: String,
    val lastOpenedAt: Long,
)
