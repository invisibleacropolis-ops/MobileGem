package com.mobilegem.gemma.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Project::class, Session::class, StoredMessage::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun coreDao(): CoreDao

    companion object {
        fun create(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "memory.db")
                .build()
    }
}
