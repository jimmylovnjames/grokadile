package com.grokadile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * xAI Grok speaks the OpenAI chat-completions schema. These DTOs mirror that
 * wire format; the repository maps them to/from the provider-agnostic domain
 * [com.grokadile.domain.model] types.
 *
 * [ChatMessageDto.content] is a [JsonElement] so the same type can carry a
 * plain string (text chat) or an OpenAI-style multimodal content array
 * (screenshot → vision).
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: JsonElement,
) {
    /** Flatten text from a string or a multimodal content array. */
    fun textContent(): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        else -> ""
    }
}

@Serializable
data class ChatCompletionRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
data class ChatChoiceDto(
    val index: Int = 0,
    val message: ChatMessageDto,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ChatCompletionResponseDto(
    val id: String? = null,
    val model: String = "",
    val choices: List<ChatChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)
