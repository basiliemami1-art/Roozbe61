package com.gozar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, ServerEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GozarDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var instance: GozarDatabase? = null

        fun get(context: Context): GozarDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GozarDatabase::class.java,
                "gozar.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
