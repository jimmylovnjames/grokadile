package com.grokadile.domain.repository

import com.grokadile.core.common.AppResult
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatResponse

/**
 * Gateway to the Grok LLM. The default implementation talks to a Cloudflare
 * Worker that proxies xAI (keeping the API key server-side); it can also be
 * pointed straight at api.x.ai for local development.
 */
interface GrokRepository {
    suspend fun chat(request: ChatRequest): AppResult<ChatResponse>
}
