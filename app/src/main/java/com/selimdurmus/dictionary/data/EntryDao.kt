package com.selimdurmus.dictionary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class EntryDao(private val dictionary: DictionaryDb) {

    /**
     * Prefix-match FTS across both translation directions. If the FTS pass returns too few hits on
     * a long-ish query, fall back to a Damerau-Levenshtein scan over a small candidate pool so
     * typos like ``comtemplate`` still surface ``contemplate``.
     *
     * Runs on [Dispatchers.IO] — these are synchronous SQLite reads against a ~1.6M-row DB and
     * must not block the main thread (callers collect on viewModelScope, i.e. Main).
     */
    suspend fun search(
        query: String,
        limit: Int = 200,
        filter: LangFilter = LangFilter.ALL,
    ): SearchResults = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return@withContext SearchResults(emptyList())

        val fts = ftsSearch(cleaned, limit, filter)

        // Wiktionary ships "Misspelling of X" / "Alternative spelling of X" entries as their own
        // rows. If the user typed exactly one of those headwords AND it has no real-meaning
        // senses, follow the redirect to X and surface it via the suggestion banner.
        followRedirect(cleaned, fts)?.let { return@withContext it }

        // Inflected forms (Turkish "kitabı", "gidiyorum"; English "went") usually have no meaning
        // of their own — at most a grammar note. Resolve them to their lemma via the `forms` table.
        lemmaFallback(cleaned, fts, filter)?.let { return@withContext it }

        val needsFuzzy = fts.size < FUZZY_TRIGGER && cleaned.length >= FUZZY_MIN_LEN
        if (!needsFuzzy) return@withContext SearchResults(fts)

        val remaining = limit - fts.size
        if (remaining <= 0) return@withContext SearchResults(fts)
        val seen = fts.mapTo(HashSet()) { it.sourceWord to it.sourceLang }
        val fuzzy = fuzzySearch(cleaned, remaining, seen, filter)
        // Only call it a "did you mean" when the prefix scan was empty — otherwise the FTS hits
        // are the user's intent and fuzzy entries are just supplementary.
        val suggestion = if (fts.isEmpty()) fuzzy.firstOrNull()?.sourceWord else null
        SearchResults(fts + fuzzy, suggestion)
    }

    private suspend fun followRedirect(cleaned: String, fts: List<Entry>): SearchResults? {
        val matching = fts.filter { it.sourceWord.equals(cleaned, ignoreCase = true) }
        if (matching.isEmpty()) return null
        val targets = matching.mapNotNull { redirectTarget(it.targetWord) }
        // Require ALL senses of this headword to be redirects — otherwise the user's typed word
        // has real meaning that we shouldn't hide.
        if (targets.size != matching.size) return null
        val mostCommon = targets.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return null
        val targetEntries = entriesFor(mostCommon, matching.first().sourceLang)
        if (targetEntries.isEmpty()) return null
        return SearchResults(targetEntries, suggestion = mostCommon)
    }

    /**
     * When the typed word has no real translation of its own (absent, or only "inflection of X"
     * notes / a same-language definition), look it up in the `forms` table and show the first
     * lemma that does have one. Explicit "form of" links win over inflection-table links, so
     * "bana" resolves to "ben" rather than "ban".
     */
    private suspend fun lemmaFallback(
        cleaned: String,
        fts: List<Entry>,
        filter: LangFilter,
    ): SearchResults? {
        if (!hasFormsTable) return null
        val exact = fts.filter { it.sourceWord.equals(cleaned, ignoreCase = true) }
        val ownMeaning = exact.any { it.isTranslation() && filter.matches(it) }
        if (ownMeaning) {
            // A word can be both: "children" has its own (rare) translation "sübyan" but is mainly
            // the plural of "child". When it carries an inflection note, list the lemma's
            // translations right after its own, without the "Form of" banner.
            if (exact.none { it.category == INFLECTION_CATEGORY }) return null
            val (lemma, lang) = lemmasFor(cleaned).firstOrNull() ?: return null
            val lemmaEntries = entriesFor(lemma, lang).filter { filter.matches(it) && it.isTranslation() }
            if (lemmaEntries.isEmpty()) return null
            val own = fts.takeWhile { it.sourceWord.equals(cleaned, ignoreCase = true) }
            val shown = (own + lemmaEntries).mapTo(HashSet()) { it.id }
            val rest = fts.filter { it.id !in shown }
            // If the word's own translations are only guesses (MT/AI/PanLex), the lemma's
            // dictionary entries are the better answer: "went" → "go → gitmek" before "gitti".
            val ownIsGuess = own.filter { it.isTranslation() }.all { it.source in GUESS_SOURCES }
            return if (ownIsGuess) SearchResults(lemmaEntries + own + rest, suggestion = lemma, isLemma = true)
            else SearchResults(own + lemmaEntries + rest)
        }
        for ((lemma, lang, explicit) in lemmasFor(cleaned)) {
            val entries = entriesFor(lemma, lang).filter { filter.matches(it) }
            if (entries.none { it.isTranslation() }) continue
            val ids = entries.mapTo(HashSet()) { it.id }
            // Misspelling links (nasil → nasıl) read as "Showing results for", not "is a form of".
            return SearchResults(
                entries + fts.filter { it.id !in ids },
                suggestion = lemma,
                isLemma = explicit != SPELLING_LINK,
            )
        }
        return null
    }

    /** (lemma, lang, explicit) links for a typed word, best first (see ingest_forms.py). */
    private fun lemmasFor(word: String): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        dictionary.raw().rawQuery(
            """
            SELECT lemma, lang, explicit FROM forms
            WHERE (form = ? AND lang = 'tr') OR (form = ? AND lang = 'en')
            ORDER BY explicit DESC
            """.trimIndent(),
            arrayOf(word.lowercase(TURKISH), word.lowercase(Locale.ROOT)),
        ).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getInt(2))
        }
        return out
    }

    private fun Entry.isTranslation(): Boolean =
        targetLang != sourceLang && category != INFLECTION_CATEGORY

    private fun LangFilter.matches(e: Entry): Boolean = when (this) {
        LangFilter.ALL -> true
        LangFilter.EN_TR -> e.sourceLang == "en" && e.targetLang == "tr"
        LangFilter.TR_EN -> e.sourceLang == "tr" && e.targetLang == "en"
    }

    private fun redirectTarget(targetWord: String): String? {
        val lower = targetWord.lowercase()
        val prefix = REDIRECT_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return null
        val raw = targetWord.substring(prefix.length).trim().trimEnd('.')
        if (raw.isEmpty()) return null
        // Target sometimes has trailing metadata like ", from Latin..." — cut at the first such marker.
        val end = raw.indexOfAny(charArrayOf(',', ';', '(', '[', ':'))
        val word = (if (end >= 0) raw.substring(0, end) else raw).trim()
        return word.ifEmpty { null }
    }

    /** Full entry list for a single headword (used by EntryDetail). Runs off the main thread. */
    suspend fun entriesFor(word: String, lang: String): List<Entry> = withContext(Dispatchers.IO) {
        query(
        """
        SELECT id, source_word, source_lang, target_word, target_lang, pos, category, definition, sense_order,
               ${sourceCol("")}
        FROM entries
        WHERE source_word = ? AND source_lang = ?
        ORDER BY ${tierExpr("")}, category, sense_order
        """.trimIndent(),
            arrayOf(word, lang),
        )
    }

    private fun ftsSearch(cleaned: String, limit: Int, filter: LangFilter): List<Entry> {
        val pattern = sanitizeFtsToken(cleaned) + "*"
        // Ranking, top to bottom:
        //   1. exact headword match (case-insensitive)
        //   2. provenance tier — dictionary translations, then Claude per-sense, then PanLex, then
        //      OPUS-MT, then same-language glosses / inflection notes (see tierExpr)
        //   3. shorter source words — closer length to the query usually means closer relevance
        //   4. language / category / sense for stable, deterministic order
        return query(
            """
            SELECT e.id, e.source_word, e.source_lang, e.target_word, e.target_lang,
                   e.pos, e.category, e.definition, e.sense_order, ${sourceCol("e.")}
            FROM entries_fts f
            JOIN entries e ON e.id = f.rowid
            WHERE entries_fts MATCH ?${filter.entriesClause("e.")}
            ORDER BY (LOWER(e.source_word) = LOWER(?)) DESC,
                     ${tierExpr("e.")} ASC,
                     LENGTH(e.source_word) ASC,
                     e.source_lang,
                     e.category,
                     e.sense_order
            LIMIT ?
            """.trimIndent(),
            arrayOf(pattern, cleaned, limit.toString()),
        )
    }

    private suspend fun fuzzySearch(
        cleaned: String,
        limit: Int,
        exclude: Set<Pair<String, String>>,
        filter: LangFilter,
    ): List<Entry> {
        val lowered = cleaned.lowercase()
        val firstChar = lowered.first()
        val minLen = (lowered.length - FUZZY_MAX_DISTANCE).coerceAtLeast(2)
        val maxLen = lowered.length + FUZZY_MAX_DISTANCE

        val ranked = candidateWords(firstChar, minLen, maxLen, filter)
            .asSequence()
            .filter { (word, lang) -> (word to lang) !in exclude }
            .map { (word, lang) ->
                Triple(word, lang, damerauLevenshtein(lowered, word.lowercase(), FUZZY_MAX_DISTANCE))
            }
            .filter { it.third <= FUZZY_MAX_DISTANCE }
            .sortedBy { it.third }
            .take(FUZZY_MAX_WORDS)
            .toList()

        return ranked.flatMap { (word, lang, _) -> entriesFor(word, lang) }.take(limit)
    }

    private fun candidateWords(
        firstChar: Char,
        minLen: Int,
        maxLen: Int,
        filter: LangFilter,
    ): List<Pair<String, String>> {
        // Range [firstChar, firstChar+1) on the indexed (source_word, source_lang) tuple — uses
        // idx_entries_source for a prefix scan, then the length filter prunes in-row.
        val lower = firstChar.toString()
        val upper = (firstChar.code + 1).toChar().toString()
        val out = ArrayList<Pair<String, String>>()
        // minLen/maxLen are inlined as integer literals, NOT bound as args. rawQuery binds every
        // arg as TEXT, and SQLite treats an INTEGER (the LENGTH result) as less than ANY text
        // value — so `LENGTH(...) BETWEEN '4' AND '8'` is false for every row, which silently
        // returned zero fuzzy candidates (the whole "did you mean" path was dead). These are
        // app-computed Ints, so interpolating them is injection-safe and keeps the compare integer.
        dictionary.raw().rawQuery(
            """
            SELECT DISTINCT source_word, source_lang FROM entries
            WHERE source_word >= ? AND source_word < ?
              AND LENGTH(source_word) BETWEEN $minLen AND $maxLen
              ${filter.entriesClause()}
            """.trimIndent(),
            arrayOf(lower, upper),
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    /**
     * Ranking tier (lower sorts first), for cross-language rows by provenance:
     *   0  direct Wiktionary translations (source='wiktionary')
     *   1  other dictionary data: Wiktionary pairs read backwards (source='wiktionary-rev') and
     *      the Turkish Wiktionary edition (source='trwiktionary')
     *   2  Claude per-sense machine translations (source='llm')
     *   3  PanLex CC0 dictionary entries (source='panlex') — real dictionary data but noisy
     *   4  OPUS-MT per-word machine translations (source='mt') — last resort; context-free and
     *      often wrong, so it sits below every dictionary source
     *   5  same-language fallbacks (en→en glosses, Turkish→Turkish TDK definitions) and
     *      inflection notes ("definite accusative singular of kitap", category 'Inflection')
     *
     * Older DBs (shipped before the MT pass) have no `source` column; there we collapse to the
     * original two-tier split so the query still runs instead of erroring on a missing column.
     */
    private fun tierExpr(prefix: String): String {
        val cross = "${prefix}target_lang != ${prefix}source_lang AND ${prefix}category != '$INFLECTION_CATEGORY'"
        return if (hasSourceColumn)
            "CASE WHEN $cross AND ${prefix}source = 'wiktionary' THEN 0 " +
                "WHEN $cross AND ${prefix}source IN ('wiktionary-rev', 'trwiktionary') THEN 1 " +
                "WHEN $cross AND ${prefix}source = 'llm' THEN 2 " +
                "WHEN $cross AND ${prefix}source = 'panlex' THEN 3 " +
                "WHEN $cross AND ${prefix}source = 'mt' THEN 4 ELSE 5 END"
        else
            "CASE WHEN $cross THEN 0 ELSE 5 END"
    }

    /** The provenance column, or a constant on DBs that predate it. */
    private fun sourceCol(prefix: String): String =
        if (hasSourceColumn) "${prefix}source" else "'wiktionary'"

    private val hasFormsTable: Boolean by lazy {
        dictionary.raw().rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'forms'", null,
        ).use { it.moveToFirst() }
    }

    private val hasSourceColumn: Boolean by lazy {
        dictionary.raw().rawQuery("PRAGMA table_info(entries)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            var found = false
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == "source") { found = true; break }
            }
            found
        }
    }

    private fun LangFilter.entriesClause(prefix: String = ""): String = when (this) {
        LangFilter.ALL -> ""
        LangFilter.EN_TR -> " AND ${prefix}source_lang = 'en' AND ${prefix}target_lang = 'tr'"
        LangFilter.TR_EN -> " AND ${prefix}source_lang = 'tr' AND ${prefix}target_lang = 'en'"
    }

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
                    source = c.getString(9),
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

    private fun damerauLevenshtein(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > max) return Int.MAX_VALUE
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val al = a.length
        val bl = b.length
        val d = Array(al + 1) { IntArray(bl + 1) }
        for (i in 0..al) d[i][0] = i
        for (j in 0..bl) d[0][j] = j

        for (i in 1..al) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..bl) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Int.MAX_VALUE
        }
        return d[al][bl]
    }

    companion object {
        /** Category the data pipeline gives grammar-note senses (sources/parse.py INFLECTION). */
        private const val INFLECTION_CATEGORY = "Inflection"
        private val TURKISH = Locale.forLanguageTag("tr")
        /** Provenance tags for machine-made or noisy rows (see tierExpr). */
        private val GUESS_SOURCES = setOf("mt", "llm", "panlex")
        /** forms.explicit value for misspelling links (data-pipeline/ingest_spelling.py). */
        private const val SPELLING_LINK = -2

        private const val FUZZY_TRIGGER = 5
        private const val FUZZY_MIN_LEN = 4
        private const val FUZZY_MAX_DISTANCE = 2
        private const val FUZZY_MAX_WORDS = 8

        // Wiktionary gloss prefixes that mark an entry as pointing at another headword rather
        // than carrying its own meaning. Compared case-insensitively against the target_word.
        private val REDIRECT_PREFIXES = listOf(
            "misspelling of ",
            "common misspelling of ",
            "mis-spelling of ",
            "alternative spelling of ",
            "alternative form of ",
            "obsolete spelling of ",
            "archaic spelling of ",
            "rare spelling of ",
            "nonstandard spelling of ",
            "informal spelling of ",
            "eye dialect of ",
            "pronunciation spelling of ",
            "misconstruction of ",
        )
    }
}
