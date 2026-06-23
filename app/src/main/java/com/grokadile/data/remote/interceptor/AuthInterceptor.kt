package com.grokadile.data.remote.interceptor

import com.grokadile.data.remote.auth.AuthTokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Adds `Authorization: Bearer <token>` when a key is configured. */
class AuthInterceptor @Inject constructor(
    private val tokenStore: AuthTokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.get()
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
