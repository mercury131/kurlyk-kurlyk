package com.example.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.crypto.PhoneNormalizer
import com.example.data.db.UserDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
) {
    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncDeviceContacts(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext emptyMap()
        val phoneToName = mutableMapOf<String, String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val rawNum = if (numCol >= 0) cursor.getString(numCol) else null
                    val dispName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (!rawNum.isNullOrBlank() && !dispName.isNullOrBlank()) {
                        PhoneNormalizer.normalize(rawNum)?.let { e164 ->
                            phoneToName.putIfAbsent(e164, dispName.trim())
                        }
                    }
                }
            }
        }

        val users = userDao.getAllUsersOnce()
        for (u in users) {
            val matched = phoneToName[u.phone]
            if (matched != u.contactName) {
                userDao.updateContactName(u.phone, matched)
            }
        }
        phoneToName
    }
}
