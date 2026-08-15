package com.grokadile.agent

import com.grokadile.agent.builtin.VectorMemoryAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.memory.InMemoryVectorMemoryRepository
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryAgentTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val store = InMemoryVectorMemoryRepository()
    private val agent = VectorMemoryAgent(store, json)

    @Test
    fun `remember and search roundtrip`() = runTest {
        val remember = Task(
            agentId = VectorMemoryAgent.ID,
            title = "mem",
            payload = """{"mode":"remember","text":"Hokianga marae meeting Thursday 7pm","source":"calendar"}""",
        )
        assertTrue(agent.execute(remember, FakeAgentContext(remember)) is AgentResult.Success)

        val search = Task(
            agentId = VectorMemoryAgent.ID,
            title = "q",
            payload = """{"mode":"search","query":"when is marae meeting","limit":3}""",
        )
        val r2 = agent.execute(search, FakeAgentContext(search))
        assertTrue(r2 is AgentResult.Success)
        assertTrue(
            (r2 as AgentResult.Success).output!!.contains("Thursday") ||
                r2.output!!.contains("marae"),
        )
    }

    @Test
    fun `remember requires text`() = runTest {
        val task = Task(agentId = VectorMemoryAgent.ID, title = "x", payload = """{"mode":"remember"}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }

    @Test
    fun `stats reports count`() = runTest {
        store.remember("alpha")
        store.remember("beta")
        val task = Task(agentId = VectorMemoryAgent.ID, title = "s", payload = """{"mode":"stats"}""")
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("count=2"))
    }

    @Test
    fun `bare text remembers`() = runTest {
        val task = Task(agentId = VectorMemoryAgent.ID, title = "raw", payload = "plain note about truck brakes")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertTrue(store.count() >= 1)
    }
}
