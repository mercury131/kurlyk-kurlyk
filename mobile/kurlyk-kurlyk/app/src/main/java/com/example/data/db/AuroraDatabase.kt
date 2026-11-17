package com.example.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        ChatEntity::class,
        GroupEntity::class,
        MessageEntity::class,
        ReceiptEntity::class,
        FileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun groupDao(): GroupDao
    abstract fun messageDao(): MessageDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun fileDao(): FileDao
}
