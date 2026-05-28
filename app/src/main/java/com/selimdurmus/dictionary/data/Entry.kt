package com.selimdurmus.dictionary.data

data class Entry(
    val id: Long,
    val sourceWord: String,
    val sourceLang: String,
    val targetWord: String,
    val targetLang: String,
    val pos: String?,
    val category: String,
    val definition: String?,
    val senseOrder: Int,
)
