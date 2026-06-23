package com.grokadile.domain.model

/** Provider-agnostic chat primitives. The data layer maps these to/from the
 *  Grok (xAI) OpenAI-compatible wire format. */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String = "grok-2-latest",
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val stream: Boolean = false,
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class ChatResponse(
    val content: String,
    val model: String,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)
