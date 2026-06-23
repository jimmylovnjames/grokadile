package com.grokadile.data.remote.api

import com.grokadile.data.remote.dto.ChatCompletionRequestDto
import com.grokadile.data.remote.dto.ChatCompletionResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

/** Chat-completions endpoint (xAI directly, or via the Cloudflare Worker proxy). */
interface GrokApi {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Body request: ChatCompletionRequestDto,
    ): ChatCompletionResponseDto
}
