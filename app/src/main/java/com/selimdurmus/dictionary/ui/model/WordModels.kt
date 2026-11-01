package com.selimdurmus.dictionary.ui.model

import com.selimdurmus.dictionary.data.DictionaryRepository.Companion.INFLECTION_CATEGORY
import com.selimdurmus.dictionary.data.Entry

/** One numbered line on a result card: a part of speech and its translations. */
data class CardLine(
    val pos: String?,
    val terms: List<String>,
    /** "AI translation" / "machine translation" / "PanLex" when every term on the line is one. */
    val sourceLabel: String?,
)

/** A search result: one headword with its translations grouped by part of speech. */
data class WordCard(
    val word: String,
    val lang: String,
    val lines: List<CardLine>,
    /** Translations not shown on the card (open the detail page to see them). */
    val hiddenCount: Int,
    /** True when there is no cross-language translation, only same-language definitions. */
    val definitionsOnly: Boolean,
    /** Grammar note such as "plural of child", shown when the word is an inflected form. */
    val note: String?,
)

/** A meaning on the detail page: translations sharing one sense, with its gloss. */
data class SenseGroup(
    val terms: List<String>,
    val definition: String?,
    val category: String,
    val sourceLabel: String?,
    val copyText: String,
)

data class PosSection(val pos: String?, val senses: List<SenseGroup>)

data class WordDetail(
    val word: String,
    val lang: String,
    val translations: List<PosSection>,
    val definitions: List<PosSection>,
    val notes: List<String>,
)

private fun Entry.isTranslation() = targetLang != sourceLang && category != INFLECTION_CATEGORY

private fun commonLabel(entries: List<Entry>): String? =
    entries.map { it.sourceLabel }.distinct().singleOrNull()

/** Group search rows (any order) into one card per headword, keeping first-appearance order. */
fun buildCards(entries: List<Entry>, maxLines: Int = 3, maxTermsPerLine: Int = 4): List<WordCard> =
    entries.groupBy { it.sourceWord to it.sourceLang }.map { (key, rows) ->
        val translations = rows.filter { it.isTranslation() }
        val shownFrom = translations.ifEmpty { rows.filter { it.category != INFLECTION_CATEGORY } }
        val byPos = shownFrom.groupBy { it.pos?.takeIf(String::isNotBlank) }
        var total = 0
        var shown = 0
        val lines = byPos.entries.mapIndexedNotNull { index, (pos, group) ->
            val terms = group.map { it.targetWord }.distinctBy { it.lowercase() }
            total += terms.size
            if (index >= maxLines) return@mapIndexedNotNull null
            val visible = terms.take(maxTermsPerLine)
            shown += visible.size
            CardLine(pos, visible, commonLabel(group))
        }
        WordCard(
            word = key.first,
            lang = key.second,
            lines = lines,
            hiddenCount = (total - shown).coerceAtLeast(0),
            definitionsOnly = translations.isEmpty(),
            note = rows.firstOrNull { it.category == INFLECTION_CATEGORY }?.targetWord,
        )
    }

/** Everything known about one headword, arranged for the detail page. */
fun buildDetail(word: String, lang: String, entries: List<Entry>): WordDetail {
    fun sections(rows: List<Entry>): List<PosSection> =
        rows.groupBy { it.pos?.takeIf(String::isNotBlank) }.map { (pos, posRows) ->
            val senses = posRows.groupBy { it.senseOrder to it.source }.values.map { sense ->
                val terms = sense.map { it.targetWord }.distinctBy { it.lowercase() }
                SenseGroup(
                    terms = terms,
                    definition = sense.firstNotNullOfOrNull { e ->
                        e.definition?.takeIf { it.isNotBlank() && !terms.contains(it) }
                    },
                    category = sense.first().category,
                    sourceLabel = commonLabel(sense),
                    copyText = "$word → ${terms.joinToString(", ")}",
                )
            }
            PosSection(pos, senses)
        }
    return WordDetail(
        word = word,
        lang = lang,
        translations = sections(entries.filter { it.isTranslation() }),
        definitions = sections(entries.filter { !it.isTranslation() && it.category != INFLECTION_CATEGORY }),
        notes = entries.filter { it.category == INFLECTION_CATEGORY }.map { it.targetWord }.distinct(),
    )
}

/** Readable part-of-speech label (Kaikki abbreviations; TDK labels are already words). */
fun posLabel(pos: String?): String? = when (pos) {
    null -> null
    "adj" -> "adjective"
    "adv" -> "adverb"
    "intj" -> "interjection"
    "prep" -> "preposition"
    "pron" -> "pronoun"
    "conj" -> "conjunction"
    "num" -> "numeral"
    "name" -> "proper noun"
    "det" -> "determiner"
    "abbrev" -> "abbreviation"
    "prep_phrase" -> "prepositional phrase"
    else -> pos.replace('_', ' ')
}

fun langLabel(lang: String): String = lang.uppercase()
