package com.grokadile.agent

import com.grokadile.agent.builtin.ClipboardAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import com.grokadile.testutil.FakeClipboardProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardAgentTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val clip = FakeClipboardProvider("hello")
    private val agent = ClipboardAgent(clip, json)

    @Test
    fun `get returns clipboard text`() = runTest {
        val task = Task(agentId = ClipboardAgent.ID, title = "g", payload = """{"mode":"get"}""")
        val ctx = FakeAgentContext(task)
        val result = agent.execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertEquals("hello", (result as AgentResult.Success).output)
        assertEquals("hello", ctx.fakeMemory.store["last_clipboard"])
    }

    @Test
    fun `set writes text`() = runTest {
        val task = Task(
            agentId = ClipboardAgent.ID,
            title = "s",
            payload = """{"mode":"set","text":"copied"}""",
        )
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertEquals("copied", clip.text)
    }

    @Test
    fun `bare text sets clipboard`() = runTest {
        val task = Task(agentId = ClipboardAgent.ID, title = "s", payload = "plain note")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertEquals("plain note", clip.text)
    }

    @Test
    fun `clear empties clipboard`() = runTest {
        val task = Task(agentId = ClipboardAgent.ID, title = "c", payload = """{"mode":"clear"}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertNull(clip.text)
    }

    @Test
    fun `unknown mode fails`() = runTest {
        val task = Task(agentId = ClipboardAgent.ID, title = "x", payload = """{"mode":"explode"}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }
}
