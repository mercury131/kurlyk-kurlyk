package com.example.data.api

import android.annotation.SuppressLint
import com.example.BuildConfig
import com.example.data.prefs.SessionPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Singleton
class OkHttpProvider @Inject constructor(
    private val sessionPrefs: SessionPrefs,
) {
    private val userAgent = "AuroraMessenger-Android/${BuildConfig.VERSION_NAME}"

    private val userAgentInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()
        chain.proceed(req)
    }

    @Volatile
    private var cachedAllowSelfSigned: Boolean? = null
    @Volatile
    private var cachedClient: OkHttpClient? = null

    fun getClient(allowSelfSigned: Boolean? = null): OkHttpClient {
        val selfSigned = allowSelfSigned ?: runBlocking { sessionPrefs.settings.first().allowSelfSigned }
        val existing = cachedClient
        if (existing != null && cachedAllowSelfSigned == selfSigned) {
            return existing
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        if (selfSigned) {
            applyTrustAll(builder)
        }

        return builder.build().also {
            cachedAllowSelfSigned = selfSigned
            cachedClient = it
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun applyTrustAll(builder: OkHttpClient.Builder) {
        val trustAllCert = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllCert), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustAllCert)
        builder.hostnameVerifier { _, _ -> true }
    }
}
