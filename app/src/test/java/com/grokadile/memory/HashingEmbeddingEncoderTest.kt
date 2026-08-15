package com.grokadile.memory

import com.grokadile.core.math.VectorMath
import com.grokadile.domain.memory.HashingEmbeddingEncoder
import com.grokadile.domain.memory.InMemoryVectorMemoryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashingEmbeddingEncoderTest {
    private val encoder = HashingEmbeddingEncoder()

    @Test
    fun `same text same embedding`() {
        val a = encoder.embed("Bank OTP is 482913")
        val b = encoder.embed("Bank OTP is 482913")
        assertEquals(1f, VectorMath.cosine(a, b), 1e-5f)
    }

    @Test
    fun `related texts rank higher than unrelated`() = runTest {
        val store = InMemoryVectorMemoryRepository(encoder)
        store.remember("Bank sent one-time password 482913 for login", source = "sms")
        store.remember("Recipe for banana bread with walnuts", source = "notes")
        store.remember("WiFi password is blue-cactus-9", source = "notes")

        val hits = store.search("OTP code from bank", limit = 3)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().item.text.contains("482913"))
        assertTrue(hits.first().score > hits.last().score || hits.size == 1)
    }

    @Test
    fun `forget removes item`() = runTest {
        val store = InMemoryVectorMemoryRepository(encoder)
        val item = store.remember("ephemeral note")
        assertEquals(1, store.count())
        assertTrue(store.forget(item.id))
        assertEquals(0, store.count())
    }
}
