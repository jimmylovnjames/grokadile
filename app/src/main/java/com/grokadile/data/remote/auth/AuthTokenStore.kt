package com.grokadile.data.remote.auth

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, in-memory holder for the current bearer token so the OkHttp
 * [com.grokadile.data.remote.interceptor.AuthInterceptor] can read it without
 * blocking on DataStore. [SettingsRepository] keeps this in sync.
 *
 * Not needed when all traffic is proxied through a Worker that owns the key —
 * in that case the token simply stays null and no header is added.
 */
@Singleton
class AuthTokenStore @Inject constructor() {
    private val token = AtomicReference<String?>(null)

    fun set(value: String?) = token.set(value?.takeIf { it.isNotBlank() })
    fun get(): String? = token.get()
}
