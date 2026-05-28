package com.selimdurmus.dictionary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Recent::class, SearchStat::class],
    version = 1,
    exportSchema = false,
)
abstract class UserDb : RoomDatabase() {

    abstract fun recents(): RecentDao
    abstract fun stats(): StatsDao

    companion object {
        fun build(context: Context): UserDb =
            Room.databaseBuilder(context, UserDb::class.java, "user.db").build()
    }
}
