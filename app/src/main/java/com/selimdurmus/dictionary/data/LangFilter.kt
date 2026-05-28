package com.selimdurmus.dictionary.data

/**
 * Restricts search results to a single translation direction.
 *
 * [ALL] is the default — current behavior with no filtering, includes English-only definition
 * rows. [EN_TR] / [TR_EN] keep only rows whose `source_lang` / `target_lang` match the direction,
 * which also excludes the en→en gloss fallback rows.
 */
enum class LangFilter { ALL, EN_TR, TR_EN }
