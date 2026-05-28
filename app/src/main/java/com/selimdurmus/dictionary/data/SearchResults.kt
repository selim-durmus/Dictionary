package com.selimdurmus.dictionary.data

/**
 * Wraps a search response so the UI can tell apart exact-prefix hits from fuzzy corrections.
 * [suggestion] is non-null only when the FTS prefix scan returned zero rows and the fuzzy
 * fallback produced something — in that case it carries the top corrected headword so the UI
 * can render a "Did you mean" banner.
 */
data class SearchResults(
    val entries: List<Entry>,
    val suggestion: String? = null,
)
