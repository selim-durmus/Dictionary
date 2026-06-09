package com.selimdurmus.dictionary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Bundled read-only dictionary. The .db file is shipped in /assets/, copied to the app's
 * internal storage on first launch, and opened as read-only SQLite.
 *
 * Bump [VERSION] whenever a new dictionary.db ships so the copy is refreshed.
 */
class DictionaryDb private constructor(private val db: SQLiteDatabase) {

    fun raw(): SQLiteDatabase = db

    fun close() {
        if (db.isOpen) db.close()
    }

    companion object {
        private const val ASSET_NAME = "dictionary.db"
        private const val LOCAL_NAME = "dictionary.db"
        // 3: en→tr OPUS-MT translations added to fill the en→en gloss gap (+ `source` column).
        private const val VERSION = 3
        private const val VERSION_FILE = "dictionary.version"

        fun open(context: Context): DictionaryDb {
            val dbFile = context.getDatabasePath(LOCAL_NAME).also { it.parentFile?.mkdirs() }
            val versionFile = File(dbFile.parentFile, VERSION_FILE)

            val needsCopy = !dbFile.exists() || readVersion(versionFile) != VERSION
            if (needsCopy) {
                copyFromAssets(context, dbFile)
                versionFile.writeText(VERSION.toString())
            }

            val sqlite = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                /* cursorFactory = */ null,
                SQLiteDatabase.OPEN_READONLY,
            )
            return DictionaryDb(sqlite)
        }

        private fun readVersion(file: File): Int? =
            runCatching { file.readText().trim().toInt() }.getOrNull()

        private fun copyFromAssets(context: Context, dest: File) {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
