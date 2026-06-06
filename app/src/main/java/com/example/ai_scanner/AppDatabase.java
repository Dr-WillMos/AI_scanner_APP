package com.example.ai_scanner;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {HistoryEntity.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract HistoryDao historyDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE history ADD COLUMN request_id TEXT DEFAULT NULL");
            database.execSQL("ALTER TABLE history ADD COLUMN upload_status TEXT DEFAULT NULL");
            database.execSQL("ALTER TABLE history ADD COLUMN error_message TEXT DEFAULT NULL");
            database.execSQL("ALTER TABLE history ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE history ADD COLUMN video_uri TEXT DEFAULT NULL");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "aiscanner.db"
                    ).addMigrations(MIGRATION_1_2)
                    .build();
                }
            }
        }
        return instance;
    }
}
