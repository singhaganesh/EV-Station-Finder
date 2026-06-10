package com.ganesh.stationfinder.data.network

import com.ganesh.stationfinder.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val BASE_URL = BuildConfig.BASE_URL

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val token = AuthManager.currentAccessToken()
        val request = chain.request().newBuilder().apply {
            if (token != null) {
                header("Authorization", "Bearer $token")
            }
        }.build()
        chain.proceed(request)
    }

    // On a 401, transparently refresh the Supabase session once and retry. If the
    // refresh fails (refresh token expired), give up so the user is signed out.
    private val tokenAuthenticator = okhttp3.Authenticator { _: Route?, response: Response ->
        if (response.request.header("Authorization") == null) return@Authenticator null
        if (responseCount(response) >= 2) return@Authenticator null // already retried once

        val newToken = runBlocking { AuthManager.refreshSession() } ?: return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(logging)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(httpClient)
        .build()

    val api: OpenChargeMapApi = retrofit.create(OpenChargeMapApi::class.java)
}
