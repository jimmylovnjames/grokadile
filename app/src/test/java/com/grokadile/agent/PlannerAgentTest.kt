package com.grokadile.agent

import com.grokadile.agent.builtin.PlannerAgent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerAgentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val store = InMemoryVectorMemoryRepository()
    private val agent = PlannerAgent(json, store)

    @Test
    fun `parses fenced json plan`() {
        val raw = """
            Sure.
            ```json
            {"summary":"check then note","steps":[
              {"agentId":"device_health","title":"vitals","payload":{"mode":"status"}},
              {"agentId":"vector_memory","title":"note","payload":{"mode":"remember","text":"ok"}}
            ]}
            ```
        """.trimIndent()
        val plan = PlannerAgent.parsePlan(raw, json)
        assertNotNull(plan)
        assertEquals(2, plan!!.steps.size)
        assertEquals("device_health", plan.steps[0].agentId)
    }

    @Test
    fun `enqueues dispatchable steps`() = runTest {
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = """{"summary":"echo then remember","steps":[
                      {"agentId":"echo","title":"ping","payload":"hi"},
                      {"agentId":"vector_memory","title":"keep","payload":{"mode":"remember","text":"hi"}}
                    ]}""",
                    model = "grok-test",
                ),
            ),
        )
        val task = Task(
            agentId = PlannerAgent.ID,
            title = "plan",
            payload = """{"goal":"say hi and remember it"}""",
        )
        val ctx = FakeAgentContext(task, grok = grok)
        val result = agent.execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertEquals(2, ctx.enqueued.size)
        assertEquals("echo", ctx.enqueued[0].agentId)
        assertEquals("vector_memory", ctx.enqueued[1].agentId)
    }

    @Test
    fun `dry run does not enqueue`() = runTest {
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = """{"steps":[{"agentId":"echo","title":"x","payload":"y"}]}""",
                    model = "t",
                ),
            ),
        )
        val task = Task(
            agentId = PlannerAgent.ID,
            title = "plan",
            payload = """{"goal":"echo y","dryRun":true}""",
        )
        val ctx = FakeAgentContext(task, grok = grok)
        val result = agent.execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("dry-run"))
        assertTrue(ctx.enqueued.isEmpty())
    }

    @Test
    fun `skips unknown agent ids`() = runTest {
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = """{"steps":[
                      {"agentId":"not_a_real_agent","payload":{}},
                      {"agentId":"echo","payload":"ok"}
                    ]}""",
                    model = "t",
                ),
            ),
        )
        val task = Task(agentId = PlannerAgent.ID, title = "p", payload = """{"goal":"x"}""")
        val ctx = FakeAgentContext(task, grok = grok)
        val result = agent.execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertEquals(1, ctx.enqueued.size)
        assertEquals("echo", ctx.enqueued[0].agentId)
    }

    @Test
    fun `bare goal text works`() = runTest {
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = """{"steps":[{"agentId":"echo","payload":"g"}]}""",
                    model = "t",
                ),
            ),
        )
        val task = Task(agentId = PlannerAgent.ID, title = "p", payload = "check the phone")
        val ctx = FakeAgentContext(task, grok = grok)
        assertTrue(agent.execute(task, ctx) is AgentResult.Success)
        assertEquals("check the phone", grok.requests.single().messages.last().content.substringAfter("Goal: ").substringBefore('\n'))
    }

    @Test
    fun `retries on network error`() = runTest {
        val task = Task(agentId = PlannerAgent.ID, title = "p", payload = """{"goal":"x"}""")
        val ctx = FakeAgentContext(
            task,
            grok = FakeGrokRepository(AppResult.Failure(AppError.Network("offline"))),
        )
        assertTrue(agent.execute(task, ctx) is AgentResult.Retry)
    }

    @Test
    fun `empty goal fails`() = runTest {
        val task = Task(agentId = PlannerAgent.ID, title = "p", payload = """{"goal":"  "}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }
}
