package com.grokadile.agent

import com.grokadile.agent.builtin.DeviceHealthAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.testutil.FakeAgentContext
import com.grokadile.testutil.FakeDeviceHealthProvider
import com.grokadile.testutil.FakeTaskRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthAgentTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val health = FakeDeviceHealthProvider()
    private val tasks = FakeTaskRepository()
    private val agent = DeviceHealthAgent(health, tasks, json)

    @Test
    fun `status reports battery and network`() = runTest {
        val task = Task(agentId = DeviceHealthAgent.ID, title = "s", payload = """{"mode":"status"}""")
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        val out = (result as AgentResult.Success).output!!
        assertTrue(out.contains("battery=77%"))
        assertTrue(out.contains("wifi"))
        assertTrue(out.contains("Pixel Test"))
    }

    @Test
    fun `retry_failed requeues failed tasks`() = runTest {
        val failed = Task(
            agentId = "echo",
            title = "boom",
            status = TaskStatus.FAILED,
            attempts = 3,
            lastError = "nope",
        )
        tasks.items += failed
        val task = Task(
            agentId = DeviceHealthAgent.ID,
            title = "r",
            payload = """{"mode":"retry_failed"}""",
        )
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertEquals(TaskStatus.PENDING, tasks.items.single { it.id == failed.id }.status)
        assertEquals(0, tasks.items.single { it.id == failed.id }.attempts)
    }

    @Test
    fun `retry_failed with empty queue`() = runTest {
        val task = Task(
            agentId = DeviceHealthAgent.ID,
            title = "r",
            payload = """{"mode":"retry_failed"}""",
        )
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertEquals("no failed tasks", (result as AgentResult.Success).output)
    }

    @Test
    fun `prune removes terminal tasks`() = runTest {
        tasks.items += Task(agentId = "echo", title = "ok", status = TaskStatus.SUCCEEDED)
        tasks.items += Task(agentId = "echo", title = "live", status = TaskStatus.PENDING)
        val task = Task(agentId = DeviceHealthAgent.ID, title = "p", payload = """{"mode":"prune"}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertEquals(1, tasks.items.size)
        assertEquals("live", tasks.items.single().title)
    }
}
