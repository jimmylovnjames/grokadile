package com.grokadile.data

import com.grokadile.data.remote.dto.ChatMessageDto
import com.grokadile.data.remote.mapper.toDto
import com.grokadile.domain.model.ChatImage
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMappersTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `text-only message encodes as a JSON string`() {
        val dto = ChatMessage(ChatRole.USER, "hello").toDto()
        assertEquals("hello", dto.textContent())
        val encoded = json.encodeToString(ChatMessageDto.serializer(), dto)
        assertTrue(encoded.contains("\"content\":\"hello\""))
    }

    @Test
    fun `images become an OpenAI multimodal content array`() {
        val image = ChatImage(byteArrayOf(1, 2, 3), "image/jpeg")
        val dto = ChatMessage(ChatRole.USER, "see this", listOf(image)).toDto()
        val encoded = json.encodeToString(ChatMessageDto.serializer(), dto)
        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"type\":\"image_url\""))
        assertTrue(encoded.contains("data:image/jpeg;base64,"))
        assertEquals("see this", dto.textContent())
    }
}
