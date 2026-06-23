package com.grokadile.data.remote.mapper

import com.grokadile.data.remote.dto.ChatCompletionRequestDto
import com.grokadile.data.remote.dto.ChatMessageDto
import com.grokadile.data.remote.dto.UsageDto
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.TokenUsage

fun ChatRole.wire(): String = name.lowercase()

fun ChatMessage.toDto(): ChatMessageDto = ChatMessageDto(role = role.wire(), content = content)

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
