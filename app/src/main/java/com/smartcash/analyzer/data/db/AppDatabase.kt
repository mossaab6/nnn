package com.smartcash.analyzer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        PersonEntity::class,
        ChatMessageEntity::class,
        PendingActionEntity::class,
        NotificationLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun people(): PersonDao
    abstract fun chat(): ChatDao
    abstract fun actions(): PendingActionDao
    abstract fun notifications(): NotificationLogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smartcash.db").build()
    }
}
