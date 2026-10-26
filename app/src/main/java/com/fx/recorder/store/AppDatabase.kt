package com.fx.recorder.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, EventEntity::class, TopicEntity::class, MeetingEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun topicDao(): TopicDao
    abstract fun meetingDao(): MeetingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "fx-recorder.db",
            )
                // 开发期允许 schema 变更时重建（录音底片数据可再生；正式版补 Migration）
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
