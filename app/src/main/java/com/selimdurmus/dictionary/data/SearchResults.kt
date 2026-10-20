package com.selimdurmus.dictionary.data

/**
 * Wraps a search response so the UI can tell apart exact-prefix hits from fuzzy corrections.
 * [suggestion] is non-null when the shown entries belong to a different headword than the one
 * typed: a fuzzy correction or redirect ("Showing results for X"), or — when [isLemma] is true —
 * the lemma an inflected form was resolved to ("kitabı" → "kitap").
 */
data class SearchResults(
    val entries: List<Entry>,
    val suggestion: String? = null,
    val isLemma: Boolean = false,
)
