package com.myAllVideoBrowser.di.module

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.dao.AdHostDao
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

@Module
class DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(application: DLApplication): AppDatabase {
        return Room.databaseBuilder(application, AppDatabase::class.java, "dl.db").addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5
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

    @Singleton
    @Provides
    fun provideAdHostDao(database: AppDatabase): AdHostDao = database.adHostDao()
}