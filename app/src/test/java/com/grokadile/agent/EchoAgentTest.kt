package com.grokadile.agent

import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoAgentTest {

    @Test
    fun `echoes payload and records it in memory`() = runTest {
        val agent = EchoAgent()
        val task = Task(agentId = EchoAgent.ID, title = "echo", payload = "{\"a\":1}")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals("{\"a\":1}", (result as AgentResult.Success).output)
        assertEquals("{\"a\":1}", context.fakeMemory.store["last_echo"])
    }
}
