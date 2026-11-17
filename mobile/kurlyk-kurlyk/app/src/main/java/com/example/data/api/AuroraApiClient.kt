package com.example.data.api

import com.example.data.crypto.AuroraCrypto
import com.example.data.prefs.AuthSession
import com.example.data.prefs.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class ApiException(
    val code: Int,
    override val message: String,
) : IOException(message)

@Singleton
class AuroraApiClient @Inject constructor(
    private val okHttpProvider: OkHttpProvider,
    private val sessionPrefs: SessionPrefs,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val octetStreamType = "application/octet-stream".toMediaType()
    private val reAuthMutex = Mutex()

    suspend fun authenticate(
        domain: String,
        phone: String,
        name: String,
        secretKey: String,
    ): AuthResponse = withContext(Dispatchers.IO) {
        val keys = AuroraCrypto.deriveKeys(secretKey)
        val ts = System.currentTimeMillis() / 1000L
        val nonce = AuroraCrypto.generateNonceHex(16)
        val sig = AuroraCrypto.signAuth(keys.authKey, phone, ts, nonce)
        val reqBody = AuroraJson.encodeToString(
            AuthRequest(phone = phone, name = name, ts = ts, nonce = nonce, sig = sig)
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://$domain/chat/api/auth")
            .post(reqBody)
            .build()

        okHttpProvider.getClient().newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = parseErrorMessage(raw, "Authentication failed (${response.code})")
                throw ApiException(response.code, msg)
            }
            AuroraJson.decodeFromString<AuthResponse>(raw)
        }
    }

    suspend fun reAuthenticateCurrentSession(): AuthSession {
        return reAuthMutex.withLock {
            val current = sessionPrefs.session.value
                ?: throw ApiException(401, "No active session")
            val authResp = authenticate(
                domain = current.domain,
                phone = current.phone,
                name = current.name,
                secretKey = current.secretKey,
            )
            val updated = current.copy(
                token = authResp.token,
                tokenExpires = authResp.expires,
                name = authResp.me.name.ifBlank { current.name },
            )
            sessionPrefs.saveSession(updated)
            updated
        }
    }

    private suspend fun <T> executeAuthenticated(
        block: (session: AuthSession) -> Request,
        parser: (code: Int, headers: okhttp3.Headers, bodyBytes: ByteArray) -> T,
    ): T = withContext(Dispatchers.IO) {
        val session = sessionPrefs.session.value ?: throw ApiException(401, "Not signed in")
        val firstReq = block(session)
        okHttpProvider.getClient().newCall(firstReq).execute().use { resp ->
            if (resp.code == 401) {
                // Token expired/invalid: re-run sign-in once and retry
                val refreshed = reAuthenticateCurrentSession()
                val retryReq = block(refreshed)
                okHttpProvider.getClient().newCall(retryReq).execute().use { retryResp ->
                    val retryBytes = retryResp.body?.bytes() ?: ByteArray(0)
                    if (!retryResp.isSuccessful) {
                        val err = parseErrorMessage(String(retryBytes), "Request failed (${retryResp.code})")
                        throw ApiException(retryResp.code, err)
                    }
                    return@withContext parser(retryResp.code, retryResp.headers, retryBytes)
                }
            }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val err = parseErrorMessage(String(bytes), "Request failed (${resp.code})")
                throw ApiException(resp.code, err)
            }
            parser(resp.code, resp.headers, bytes)
        }
    }

    suspend fun getMe(): UserDto = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/me")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun updateMe(name: String): UserDto = executeAuthenticated(
        block = { s ->
            val body = AuroraJson.encodeToString(UpdateMeRequest(name)).toRequestBody(jsonMediaType)
            Request.Builder()
                .url("https://${s.domain}/chat/api/me")
                .header("Authorization", "Bearer ${s.token}")
                .put(body)
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun uploadAvatar(encryptedBytes: ByteArray): UserDto = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/me/avatar")
                .header("Authorization", "Bearer ${s.token}")
                .put(encryptedBytes.toRequestBody(octetStreamType))
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun deleteAvatar(): UserDto = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/me/avatar")
                .header("Authorization", "Bearer ${s.token}")
                .delete()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    data class AvatarDownloadResult(
        val encryptedBytes: ByteArray,
        val avatarVer: Long,
    )

    suspend fun downloadAvatar(phone: String): AvatarDownloadResult = executeAuthenticated(
        block = { s ->
            val encodedPhone = URLEncoder.encode(phone, "UTF-8")
            Request.Builder()
                .url("https://${s.domain}/chat/api/avatars/$encodedPhone")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, headers, bytes ->
            val verHeader = headers["X-Avatar-Ver"]?.toLongOrNull()
                ?: headers["ETag"]?.trim('"')?.toLongOrNull()
                ?: 0L
            AvatarDownloadResult(encryptedBytes = bytes, avatarVer = verHeader)
        },
    )

    suspend fun logout(): OkResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/logout")
                .header("Authorization", "Bearer ${s.token}")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        },
        parser = { _, _, bytes ->
            if (bytes.isEmpty()) OkResponse(true) else AuroraJson.decodeFromString(String(bytes))
        },
    )

    suspend fun getUsers(): List<UserDto> = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/users")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString<UsersResponse>(String(bytes)).users },
    )

    suspend fun getConfig(): ConfigResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/config")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun getTurn(): TurnResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/turn")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun getGroups(): List<GroupDto> = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString<GroupsResponse>(String(bytes)).groups },
    )

    suspend fun createGroup(name: String, members: List<String>): GroupDto = executeAuthenticated(
        block = { s ->
            val body = AuroraJson.encodeToString(CreateGroupRequest(name, members))
                .toRequestBody(jsonMediaType)
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups")
                .header("Authorization", "Bearer ${s.token}")
                .post(body)
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun getGroup(id: String): GroupDto = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups/$id")
                .header("Authorization", "Bearer ${s.token}")
                .get()
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun patchGroup(
        id: String,
        name: String? = null,
        add: List<String>? = null,
        remove: List<String>? = null,
    ): GroupDto = executeAuthenticated(
        block = { s ->
            val body = AuroraJson.encodeToString(PatchGroupRequest(name = name, add = add, remove = remove))
                .toRequestBody(jsonMediaType)
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups/$id")
                .header("Authorization", "Bearer ${s.token}")
                .patch(body)
                .build()
        },
        parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
    )

    suspend fun leaveGroup(id: String): OkResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups/$id/leave")
                .header("Authorization", "Bearer ${s.token}")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        },
        parser = { _, _, bytes ->
            if (bytes.isEmpty()) OkResponse(true) else AuroraJson.decodeFromString(String(bytes))
        },
    )

    suspend fun deleteGroup(id: String): OkResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/groups/$id")
                .header("Authorization", "Bearer ${s.token}")
                .delete()
                .build()
        },
        parser = { _, _, bytes ->
            if (bytes.isEmpty()) OkResponse(true) else AuroraJson.decodeFromString(String(bytes))
        },
    )

    suspend fun uploadFile(
        encryptedFile: File,
        onProgress: (Float) -> Unit = {},
    ): FileUploadResponse = withContext(Dispatchers.IO) {
        val totalSize = encryptedFile.length().coerceAtLeast(1L)
        val buildRequest: (AuthSession) -> Request = { s ->
            val body = object : RequestBody() {
                override fun contentType(): MediaType = octetStreamType
                override fun contentLength(): Long = encryptedFile.length()
                override fun writeTo(sink: BufferedSink) {
                    encryptedFile.source().use { src ->
                        var uploaded = 0L
                        val buf = okio.Buffer()
                        while (true) {
                            val read = src.read(buf, 32 * 1024L)
                            if (read == -1L) break
                            sink.write(buf, read)
                            uploaded += read
                            onProgress((uploaded.toFloat() / totalSize).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            Request.Builder()
                .url("https://${s.domain}/chat/api/files")
                .header("Authorization", "Bearer ${s.token}")
                .post(body)
                .build()
        }
        executeAuthenticated(
            block = buildRequest,
            parser = { _, _, bytes -> AuroraJson.decodeFromString(String(bytes)) },
        )
    }

    suspend fun downloadFile(
        fileId: String,
        destFile: File,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        val session = sessionPrefs.session.value ?: throw ApiException(401, "Not signed in")

        suspend fun doDownload(s: AuthSession, attemptRangeStart: Long): Int {
            val reqBuilder = Request.Builder()
                .url("https://${s.domain}/chat/api/files/$fileId")
                .header("Authorization", "Bearer ${s.token}")
                .get()
            if (attemptRangeStart > 0L) {
                reqBuilder.header("Range", "bytes=$attemptRangeStart-")
            }
            okHttpProvider.getClient().newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401) return 401
                if (!resp.isSuccessful) {
                    val err = parseErrorMessage(resp.body?.string().orEmpty(), "Download failed (${resp.code})")
                    throw ApiException(resp.code, err)
                }
                val body = resp.body ?: throw ApiException(500, "Empty response body")
                val append = resp.code == 206 && attemptRangeStart > 0L
                val totalExpected = if (append) {
                    attemptRangeStart + body.contentLength().coerceAtLeast(0L)
                } else {
                    body.contentLength().coerceAtLeast(1L)
                }
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = if (append) attemptRangeStart else 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (totalExpected > 0L) {
                                onProgress((downloaded.toFloat() / totalExpected).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                return resp.code
            }
        }

        val code = doDownload(session, existingBytes)
        if (code == 401) {
            val refreshed = reAuthenticateCurrentSession()
            val retryCode = doDownload(refreshed, if (destFile.exists()) destFile.length() else 0L)
            if (retryCode == 401) throw ApiException(401, "Unauthorized")
        }
    }

    suspend fun deleteFile(fileId: String): OkResponse = executeAuthenticated(
        block = { s ->
            Request.Builder()
                .url("https://${s.domain}/chat/api/files/$fileId")
                .header("Authorization", "Bearer ${s.token}")
                .delete()
                .build()
        },
        parser = { _, _, bytes ->
            if (bytes.isEmpty()) OkResponse(true) else AuroraJson.decodeFromString(String(bytes))
        },
    )

    private fun parseErrorMessage(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        return runCatching {
            AuroraJson.decodeFromString<ErrorResponse>(raw).error.ifBlank { fallback }
        }.getOrDefault(fallback)
    }
}
