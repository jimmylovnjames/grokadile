package com.grokadile.agent

import com.grokadile.agent.builtin.ScreenSummaryAgent
import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.ChatResponse
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import com.grokadile.testutil.FakeGrokRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSummaryAgentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeScreen(
        var available: Boolean = true,
        var dumpText: String = "=== SCREEN HIERARCHY (com.example.app) ===\nButton \"Open Settings\" {click}",
        var pkg: String? = "com.example.app",
        var title: String? = "Home",
    ) : ScreenContentProvider {
        override fun isAvailable(): Boolean = available
        override fun dump(mode: String, maxDepth: Int, maxNodes: Int): String = dumpText
        override fun activePackage(): String? = pkg
        override fun activeWindowTitle(): String? = title
    }

    @Test
    fun `summarizes screen via Grok and stores result`() = runTest {
        val screen = FakeScreen()
        val grok = FakeGrokRepository(
            AppResult.Success(ChatResponse(content = "Home screen of Example App. Key button: Open Settings.", model = "grok-test")),
        )
        val agent = ScreenSummaryAgent(screen, json)
        val task = Task(agentId = ScreenSummaryAgent.ID, title = "summarize", payload = "{}")
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals(
            "Home screen of Example App. Key button: Open Settings.",
            (result as AgentResult.Success).output,
        )
        assertEquals(
            "Home screen of Example App. Key button: Open Settings.",
            context.fakeMemory.store[ScreenSummaryAgent.KEY_LAST_SUMMARY],
        )
        assertEquals("com.example.app", context.fakeMemory.store[ScreenSummaryAgent.KEY_LAST_PKG])
        assertEquals(1, grok.requests.size)
        assertTrue(grok.requests[0].messages.any { it.content.contains("Open Settings") })
    }

    @Test
    fun `retries when accessibility unavailable`() = runTest {
        val screen = FakeScreen(available = false)
        val agent = ScreenSummaryAgent(screen, json)
        val task = Task(agentId = ScreenSummaryAgent.ID, title = "summarize", payload = "{}")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
        assertTrue((result as AgentResult.Retry).reason.contains("Accessibility"))
    }

    @Test
    fun `fails when dump returns ERROR`() = runTest {
        val screen = FakeScreen(dumpText = "ERROR: no active window root")
        val agent = ScreenSummaryAgent(screen, json)
        val task = Task(agentId = ScreenSummaryAgent.ID, title = "summarize", payload = "{}")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("ERROR:"))
    }

    @Test
    fun `forwards custom prompt to Grok`() = runTest {
        val screen = FakeScreen()
        val grok = FakeGrokRepository(
            AppResult.Success(ChatResponse(content = "Settings is the second button.", model = "grok-test")),
        )
        val agent = ScreenSummaryAgent(screen, json)
        val payload = """{"prompt":"Where is the settings button?","mode":"text"}"""
        val task = Task(agentId = ScreenSummaryAgent.ID, title = "q", payload = payload)
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        val userMsg = grok.requests[0].messages.last { it.role.name == "USER" }.content
        assertTrue(userMsg.contains("Where is the settings button?"))
    }

    @Test
    fun `retries on network error from Grok`() = runTest {
        val screen = FakeScreen()
        val grok = FakeGrokRepository(AppResult.Failure(AppError.Network("timeout")))
        val agent = ScreenSummaryAgent(screen, json)
        val task = Task(agentId = ScreenSummaryAgent.ID, title = "summarize", payload = "{}")
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
    }
}
