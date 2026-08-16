package com.grokadile.agent

import com.grokadile.agent.builtin.ScreenActAgent
import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenActionProvider
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

class ScreenActAgentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeScreen(
        var available: Boolean = true,
        var dumpText: String = "=== SCREEN HIERARCHY (com.example.app) ===\nButton \"Open Settings\" {click}  text=Open Settings",
        var pkg: String? = "com.example.app",
        var title: String? = "Home",
    ) : ScreenContentProvider {
        override fun isAvailable(): Boolean = available
        override fun dump(mode: String, maxDepth: Int, maxNodes: Int): String = dumpText
        override fun activePackage(): String? = pkg
        override fun activeWindowTitle(): String? = title
    }

    private class FakeActions(
        var available: Boolean = true,
    ) : ScreenActionProvider {
        val calls = mutableListOf<String>()
        override fun isAvailable(): Boolean = available
        override fun tap(x: Int, y: Int, durationMs: Long): String {
            calls += "tap($x,$y)"
            return "OK: tapped ($x,$y)"
        }
        override fun longPress(x: Int, y: Int, durationMs: Long): String {
            calls += "longPress($x,$y)"
            return "OK: long-pressed"
        }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): String {
            calls += "swipe"
            return "OK: swipe"
        }
        override fun clickByText(text: String, exact: Boolean): String {
            calls += "clickByText($text,exact=$exact)"
            return "OK: clicked text \"$text\""
        }
        override fun clickById(viewId: String): String {
            calls += "clickById($viewId)"
            return "OK: clicked id"
        }
        override fun typeText(text: String): String {
            calls += "typeText($text)"
            return "OK: typed"
        }
        override fun globalAction(action: String): String {
            calls += "global($action)"
            return "OK: global $action"
        }
    }

    @Test
    fun `plans and executes click_text from Grok decision`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = "{\"action\":\"click_text\",\"text\":\"Open Settings\",\"exact\":false}",
                    model = "grok-test",
                ),
            ),
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "open settings",
            payload = """{"goal":"Open Settings"}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output.contains("OK: clicked text"))
        assertEquals(listOf("clickByText(Open Settings,exact=false)"), actions.calls)
        assertEquals("Open Settings", context.fakeMemory.store[ScreenActAgent.KEY_LAST_GOAL])
        assertTrue(context.fakeMemory.store[ScreenActAgent.KEY_LAST_ACT]!!.startsWith("OK"))
    }

    @Test
    fun `dryRun returns planned JSON without executing`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = "{\"action\":\"tap\",\"x\":100,\"y\":200}",
                    model = "grok-test",
                ),
            ),
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "tap",
            payload = """{"goal":"tap somewhere","dryRun":true}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output.startsWith("DRY-RUN"))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `returns none when Grok decides no action`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = "{\"action\":\"none\",\"reason\":\"already on settings"}",
                    model = "grok-test",
                ),
            ),
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "open",
            payload = """{"goal":"open settings"}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output.contains("No action"))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `retries when accessibility unavailable`() = runTest {
        val screen = FakeScreen(available = false)
        val actions = FakeActions(available = false)
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = """{"goal":"do something"}""",
        )
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
        assertTrue((result as AgentResult.Retry).reason.contains("Accessibility"))
    }

    @Test
    fun `fails when goal is missing`() = runTest {
        val agent = ScreenActAgent(FakeScreen(), FakeActions(), json)
        val task = Task(agentId = ScreenActAgent.ID, title = "act", payload = "{}")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("goal"))
    }

    @Test
    fun `retries on network error from Grok`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = FakeGrokRepository(AppResult.Failure(AppError.Network("timeout")))
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = """{"goal":"tap login"}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
    }

    @Test
    fun `tolerates markdown-wrapped JSON from Grok`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = "```json\n{\"action\":\"global\",\"name\":\"BACK\"}\n```",
                    model = "grok-test",
                ),
            ),
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "back",
            payload = """{"goal":"go back"}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("global(BACK)"), actions.calls)
    }
}
