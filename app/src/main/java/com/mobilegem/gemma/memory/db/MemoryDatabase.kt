package com.mobilegem.gemma.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Project::class, Session::class, StoredMessage::class, Skill::class, MemoryEntry::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun coreDao(): CoreDao
    abstract fun skillDao(): SkillDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        fun create(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "memory.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
