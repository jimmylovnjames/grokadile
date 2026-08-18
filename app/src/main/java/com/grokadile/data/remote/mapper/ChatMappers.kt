package com.grokadile.data.remote.mapper

import com.grokadile.data.remote.dto.ChatCompletionRequestDto
import com.grokadile.data.remote.dto.ChatMessageDto
import com.grokadile.data.remote.dto.UsageDto
import com.grokadile.domain.model.ChatImage
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.TokenUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

fun ChatRole.wire(): String = name.lowercase()

fun ChatMessage.toDto(): ChatMessageDto {
    if (images.isEmpty()) {
        return ChatMessageDto(role = role.wire(), content = JsonPrimitive(content))
    }
    return ChatMessageDto(
        role = role.wire(),
        content = buildMultimodalContent(content, images),
    )
}

fun ChatRequest.toDto(): ChatCompletionRequestDto = ChatCompletionRequestDto(
    model = model,
    messages = messages.map { it.toDto() },
    temperature = temperature,
    maxTokens = maxTokens,
    stream = stream,
)

fun UsageDto.toDomain(): TokenUsage = TokenUsage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
)

internal fun buildMultimodalContent(text: String, images: List<ChatImage>): JsonArray =
    buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
        images.forEach { image ->
            val dataUrl = "data:${image.mimeType};base64,${
                Base64.getEncoder().encodeToString(image.bytes)
            }"
            add(
                buildJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", dataUrl)
                            put("detail", "high")
                        },
                    )
                },
            )
        }
    }
