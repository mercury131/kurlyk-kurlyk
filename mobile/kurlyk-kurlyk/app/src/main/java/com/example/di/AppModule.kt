package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.db.AuroraDatabase
import com.example.data.db.ChatDao
import com.example.data.db.FileDao
import com.example.data.db.GroupDao
import com.example.data.db.MessageDao
import com.example.data.db.ReceiptDao
import com.example.data.db.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AuroraDatabase {
        return Room.databaseBuilder(
            context,
            AuroraDatabase::class.java,
            "aurora_messenger.db",
        ).fallbackToDestructiveMigration(false).build()
    }

    @Provides
    fun provideUserDao(db: AuroraDatabase): UserDao = db.userDao()

    @Provides
    fun provideChatDao(db: AuroraDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideGroupDao(db: AuroraDatabase): GroupDao = db.groupDao()

    @Provides
    fun provideMessageDao(db: AuroraDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideReceiptDao(db: AuroraDatabase): ReceiptDao = db.receiptDao()

    @Provides
    fun provideFileDao(db: AuroraDatabase): FileDao = db.fileDao()
}
