package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AuroraDatabase
import com.example.data.db.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeduplicationTest {

    private lateinit var db: AuroraDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `duplicate messages with same sender and msgId are ignored`() = runBlocking {
        val dao = db.messageDao()
        val msg = MessageEntity(
            msgId = "uuid-123",
            chatId = "+79990000002",
            sender = "+79990000002",
            kind = "text",
            text = "Hello",
            createdAt = 1700000000000L,
            status = "delivered",
            direction = "in",
        )
        val firstRow = dao.insertIgnore(msg)
        val duplicateRow = dao.insertIgnore(msg.copy(text = "Duplicate"))
        assertEquals(true, firstRow > 0)
        assertEquals(-1L, duplicateRow)

        val stored = dao.observeMessages("+79990000002").first()
        assertEquals(1, stored.size)
        assertEquals("Hello", stored[0].text)
    }
}
