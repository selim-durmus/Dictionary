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
    /** Provenance tag from the data pipeline: wiktionary, wiktionary-rev, trwiktionary, tdk, panlex, llm, mt. */
    val source: String = "wiktionary",
) {
    /** Short label for rows that aren't hand-written dictionary data, so guesses are visible. */
    val sourceLabel: String?
        get() = when (source) {
            "mt" -> "machine translation"
            "llm" -> "AI translation"
            "panlex" -> "PanLex"
            else -> null
        }
}
