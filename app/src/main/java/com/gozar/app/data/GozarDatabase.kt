package com.gozar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SourceEntity::class, ServerEntity::class],
    version = 5,
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

        /**
         * Adds the delay measured through the proxy. Written out rather than
         * destructive: the handshake results in `latency` still narrow the field
         * for the next measurement pass, and re-earning them takes minutes.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN realDelay INTEGER NOT NULL DEFAULT -1",
                )
            }
        }

        /** Adds the throughput measured through the proxy. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN speedKb INTEGER NOT NULL DEFAULT -1",
                )
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
