package com.myAllVideoBrowser.di.module

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.dao.ConfigDao
import com.myAllVideoBrowser.data.local.room.dao.HistoryDao
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.dao.VideoDao
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

class UserSqlUtils {
    var createTable = "CREATE TABLE IF NOT EXISTS AdHost (host TEXT NOT NULL, PRIMARY KEY(host))"
}

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(UserSqlUtils().createTable)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD progressDownloaded INTEGER DEFAULT 0 NOT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD progressTotal INTEGER DEFAULT 0 NOT NULL")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE PageInfo ADD COLUMN `order` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS AdHost")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE VideoInfo ADD COLUMN isLive INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE VideoInfo ADD COLUMN isDetectedBySuperX INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ProgressInfo_new (
                id TEXT NOT NULL PRIMARY KEY,
                downloadId INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                thumbnail TEXT NOT NULL DEFAULT '',
                progressDownloaded INTEGER NOT NULL DEFAULT 0,
                progressTotal INTEGER NOT NULL DEFAULT 0,
                downloadStatus INTEGER NOT NULL,
                isLive INTEGER NOT NULL,
                isM3u8 INTEGER NOT NULL,
                isRegularDownload INTEGER NOT NULL DEFAULT 0,
                isDetectedBySuperX INTEGER NOT NULL DEFAULT 0,
                fragmentsDownloaded INTEGER NOT NULL,
                fragmentsTotal INTEGER NOT NULL DEFAULT 1,
                infoLine TEXT NOT NULL
            )
        """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO ProgressInfo_new (
                id, downloadId, progressDownloaded, progressTotal, 
                downloadStatus, isLive, isM3u8, fragmentsDownloaded, 
                fragmentsTotal, infoLine
            )
            SELECT 
                id, downloadId, progressDownloaded, progressTotal, 
                downloadStatus, isLive, isM3u8, fragmentsDownloaded, 
                fragmentsTotal, infoLine 
            FROM ProgressInfo
        """.trimIndent()
        )

        db.execSQL("DROP TABLE ProgressInfo")
        db.execSQL("ALTER TABLE ProgressInfo_new RENAME TO ProgressInfo")
    }
}

@Module
class DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(application: DLApplication): AppDatabase {
        return Room.databaseBuilder(application, AppDatabase::class.java, "dl.db").addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9
        ).build()
    }

    @Singleton
    @Provides
    fun provideConfigDao(database: AppDatabase): ConfigDao = database.configDao()

    @Singleton
    @Provides
    fun provideCommentDao(database: AppDatabase): VideoDao = database.videoDao()

    @Singleton
    @Provides
    fun provideProgressDao(database: AppDatabase): ProgressDao = database.progressDao()

    @Singleton
    @Provides
    fun provideHistoryDao(database: AppDatabase): HistoryDao = database.historyDao()

    @Singleton
    @Provides
    fun providePageDao(database: AppDatabase): PageDao = database.pageDao()
}