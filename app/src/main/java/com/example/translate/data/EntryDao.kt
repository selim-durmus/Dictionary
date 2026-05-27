package com.example.translate.data

import android.database.sqlite.SQLiteDatabase

class EntryDao(private val dictionary: DictionaryDb) {

    /**
     * Prefix-match FTS across both translation directions. The query escapes any character that
     * the FTS4 query parser treats specially, then suffixes ``*`` for autocomplete.
     */
    suspend fun search(query: String, limit: Int = 200): List<Entry> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return emptyList()
        val pattern = sanitizeFtsToken(cleaned) + "*"
        return query(
            """
            SELECT e.id, e.source_word, e.source_lang, e.target_word, e.target_lang,
                   e.pos, e.category, e.definition, e.sense_order
            FROM entries_fts f
            JOIN entries e ON e.id = f.rowid
            WHERE entries_fts MATCH ?
            ORDER BY e.source_lang, e.category, e.sense_order
            LIMIT ?
            """.trimIndent(),
            arrayOf(pattern, limit.toString()),
        )
    }

    /** Full entry list for a single headword (used by EntryDetail). */
    suspend fun entriesFor(word: String, lang: String): List<Entry> = query(
        """
        SELECT id, source_word, source_lang, target_word, target_lang, pos, category, definition, sense_order
        FROM entries
        WHERE source_word = ? AND source_lang = ?
        ORDER BY category, sense_order
        """.trimIndent(),
        arrayOf(word, lang),
    )

    private fun query(sql: String, args: Array<String>): List<Entry> {
        val db = dictionary.raw()
        val out = ArrayList<Entry>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    id = c.getLong(0),
                    sourceWord = c.getString(1),
                    sourceLang = c.getString(2),
                    targetWord = c.getString(3),
                    targetLang = c.getString(4),
                    pos = c.getStringOrNull(5),
                    category = c.getString(6),
                    definition = c.getStringOrNull(7),
                    senseOrder = c.getInt(8),
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.getStringOrNull(idx: Int): String? =
        if (isNull(idx)) null else getString(idx)

    private fun sanitizeFtsToken(s: String): String {
        // FTS4 query operators we strip rather than escape — easier than reasoning about each.
        // After cleanup the query is space-separated tokens (implicit AND in FTS4); the trailing
        // ``*`` we append in `search()` becomes a prefix match on the final token.
        return s.replace(Regex("[\"*:\\-()]"), " ").trim().replace(Regex("\\s+"), " ")
    }
}
