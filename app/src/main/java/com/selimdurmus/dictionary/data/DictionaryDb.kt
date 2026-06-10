package com.selimdurmus.dictionary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.selimdurmus.dictionary.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Bundled read-only dictionary. The .db file is shipped in /assets/, copied to the app's
 * internal storage on first launch, and opened as read-only SQLite.
 *
 * The asset is large (~hundreds of MB) and compressed inside the APK, so the copy must run off
 * the main thread — call [prepare] (suspending, on IO) before [open]. [isPrepared] is a cheap
 * synchronous check so warm launches skip the loading screen.
 *
 * Re-copy is keyed off [BuildConfig.VERSION_CODE]: every release bumps it, which forces a fresh
 * copy whenever a new APK (and therefore possibly a new dictionary.db) ships. This avoids the
 * stale-DB trap where a hand-maintained version int gets stamped against the wrong asset.
 */
class DictionaryDb private constructor(private val db: SQLiteDatabase) {

    fun raw(): SQLiteDatabase = db

    fun close() {
        if (db.isOpen) db.close()
    }

    companion object {
        private const val ASSET_NAME = "dictionary.db"
        private const val LOCAL_NAME = "dictionary.db"
        private const val STAMP_FILE = "dictionary.version"

        /** Cheap, synchronous: is the local copy present and current for this app version? */
        fun isPrepared(context: Context): Boolean {
            val dbFile = context.getDatabasePath(LOCAL_NAME)
            if (!dbFile.exists()) return false
            return readStamp(File(dbFile.parentFile, STAMP_FILE)) == currentStamp()
        }

        /** Copy the bundled DB out of assets if missing or stale. Idempotent; runs on IO. */
        suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(LOCAL_NAME).also { it.parentFile?.mkdirs() }
            val stampFile = File(dbFile.parentFile, STAMP_FILE)
            if (dbFile.exists() && readStamp(stampFile) == currentStamp()) return@withContext
            copyFromAssets(context, dbFile)
            stampFile.writeText(currentStamp())
        }

        /** Open the already-[prepare]d local copy read-only. */
        fun open(context: Context): DictionaryDb {
            val dbFile = context.getDatabasePath(LOCAL_NAME)
            val sqlite = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                /* cursorFactory = */ null,
                SQLiteDatabase.OPEN_READONLY,
            )
            return DictionaryDb(sqlite)
        }

        private fun currentStamp(): String = BuildConfig.VERSION_CODE.toString()

        private fun readStamp(file: File): String? =
            runCatching { file.readText().trim() }.getOrNull()

        private fun copyFromAssets(context: Context, dest: File) {
            // assets.open() transparently inflates the compressed asset as it streams.
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
