package com.grokadile.di

import javax.inject.Qualifier

/** Application-lifetime [kotlinx.coroutines.CoroutineScope] (SupervisorJob). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Retrofit instance targeting the Grok chat endpoint (xAI or Worker proxy). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GrokRetrofit

/** Retrofit instance targeting the Cloudflare Worker control plane. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudflareRetrofit
