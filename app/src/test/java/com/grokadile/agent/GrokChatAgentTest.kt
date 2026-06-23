package com.grokadile.agent

import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentResult
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

    @Test
    fun `returns success with reply on ok`() = runTest {
        val agent = GrokChatAgent(json)
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val context = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Success(ChatResponse("hello", "grok-2"))),
        )

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals("hello", (result as AgentResult.Success).output)
        assertEquals("hello", context.fakeMemory.store["last_reply"])
    }

    @Test
    fun `retries on network error`() = runTest {
        val agent = GrokChatAgent(json)
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val context = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Failure(AppError.Network("offline"))),
        )

        assertTrue(agent.execute(task, context) is AgentResult.Retry)
    }

    @Test
    fun `fails on http 400`() = runTest {
        val agent = GrokChatAgent(json)
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "{\"prompt\":\"hi\"}")
        val context = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Failure(AppError.Http(400, "bad request"))),
        )

        assertTrue(agent.execute(task, context) is AgentResult.Failure)
    }

    @Test
    fun `fails on malformed payload`() = runTest {
        val agent = GrokChatAgent(json)
        val task = Task(agentId = GrokChatAgent.ID, title = "ask", payload = "not-json")

        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }
}
