package com.gozar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SourceEntity::class, ServerEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class GozarDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun serverDao(): ServerDao

    companion object {

        /**
         * Adds the domestic-entry flag. Written out rather than falling back to
         * a destructive migration: rebuilding the table would throw away every
         * latency result the user has collected, and those take minutes to earn
         * back.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN domesticEntry INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_servers_domesticEntry " +
                        "ON servers (domesticEntry)",
                )
            }
        }

        /** Adds the quota a paid subscription reports about itself. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("upload", "download", "total", "expiresAt")) {
                    db.execSQL(
                        "ALTER TABLE sources ADD COLUMN $column INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
        }

        @Volatile
        private var instance: GozarDatabase? = null

        fun get(context: Context): GozarDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GozarDatabase::class.java,
                "gozar.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
