package com.grokadile.core

import com.grokadile.core.common.JsonText
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTextTest {
    @Test
    fun `strips markdown fences`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertEquals("{\"a\":1}", JsonText.stripFences(raw))
    }

    @Test
    fun `extracts object from prose`() {
        val raw = "Here you go:\n{\"steps\":[]}\nThanks"
        assertEquals("{\"steps\":[]}", JsonText.extractObject(raw))
    }
}
