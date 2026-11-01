package com.selimdurmus.dictionary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Recent::class, SearchStat::class, Favorite::class],
    version = 2,
    exportSchema = false,
)
abstract class UserDb : RoomDatabase() {

    abstract fun recents(): RecentDao
    abstract fun stats(): StatsDao
    abstract fun favorites(): FavoriteDao

    companion object {
        /** v2 adds starred words; recents and Top 50 carry over untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorites (" +
                        "word TEXT NOT NULL, lang TEXT NOT NULL, addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(word, lang))"
                )
            }
        }

        fun build(context: Context): UserDb =
            Room.databaseBuilder(context, UserDb::class.java, "user.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
