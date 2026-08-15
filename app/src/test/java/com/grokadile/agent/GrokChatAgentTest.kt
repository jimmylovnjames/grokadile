package com.grokadile.agent

import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.memory.InMemoryVectorMemoryRepository
import com.grokadile.domain.model.ChatResponse
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import com.grokadile.testutil.FakeGrokRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokChatAgentTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val store = InMemoryVectorMemoryRepository()

    private fun agent() = GrokChatAgent(json, store)

    @Test
    fun `returns success with reply on ok`() = runTest {
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val grok = FakeGrokRepository(AppResult.Success(ChatResponse("hello", "grok-2")))
        val context = FakeAgentContext(task, grok = grok)

        val result = agent().execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals("hello", (result as AgentResult.Success).output)
        assertEquals("hello", context.fakeMemory.store["last_reply"])
        assertTrue(store.count() >= 1)
    }

    @Test
    fun `injects matching memories into the system prompt`() = runTest {
        store.remember("Bank OTP channel is SMS only", source = "policy")
        val task = Task(
            agentId = GrokChatAgent.ID,
            title = "ask",
            payload = """{"prompt":"how do bank OTP codes arrive via SMS","useMemory":true,"remember":false}""",
        )
        val grok = FakeGrokRepository(AppResult.Success(ChatResponse("via SMS", "grok-2")))
        agent().execute(task, FakeAgentContext(task, grok = grok))

        val system = grok.requests.single().messages.first { it.role.name == "SYSTEM" }.content
        assertTrue(system.contains("SMS"))
    }

    @Test
    fun `skips memory when useMemory is false`() = runTest {
        store.remember("secret token lives in the vault")
        val task = Task(
            agentId = GrokChatAgent.ID,
            title = "ask",
            payload = """{"prompt":"vault","useMemory":false,"remember":false}""",
        )
        val grok = FakeGrokRepository(AppResult.Success(ChatResponse("ok", "grok-2")))
        agent().execute(task, FakeAgentContext(task, grok = grok))
        val system = grok.requests.single().messages.first { it.role.name == "SYSTEM" }.content
        assertTrue(!system.contains("secret token"))
    }

    @Test
    fun `retries on network error`() = runTest {
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val context = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Failure(AppError.Network("offline"))),
        )

        assertTrue(agent().execute(task, context) is AgentResult.Retry)
    }

    @Test
    fun `fails on http 400`() = runTest {
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val context = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Failure(AppError.Http(400, "bad request"))),
        )

        assertTrue(agent().execute(task, context) is AgentResult.Failure)
    }

    @Test
    fun `fails on malformed payload`() = runTest {
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "not-json")
        assertTrue(agent().execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }
}
