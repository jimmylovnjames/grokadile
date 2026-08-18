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
        private val dumps: MutableList<String> = mutableListOf(
            "=== SCREEN HIERARCHY (com.example.app) ===\nButton \"Open Settings\" {click}",
        ),
        var pkg: String? = "com.example.app",
        var title: String? = "Home",
    ) : ScreenContentProvider {
        private var dumpIdx = 0
        var dumpCalls = 0

        override fun isAvailable(): Boolean = available
        override fun dump(mode: String, maxDepth: Int, maxNodes: Int): String {
            dumpCalls++
            val d = dumps.getOrElse(dumpIdx) { dumps.lastOrNull() ?: "" }
            if (dumpIdx < dumps.lastIndex) dumpIdx++
            return d
        }
        override fun activePackage(): String? = pkg
        override fun activeWindowTitle(): String? = title

        fun setDumps(vararg values: String) {
            dumps.clear()
            dumps.addAll(values)
            dumpIdx = 0
            dumpCalls = 0
        }
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

    private fun grok(vararg bodies: String): FakeGrokRepository {
        val mapped = bodies.map {
            AppResult.Success(ChatResponse(content = it, model = "grok-test")) as AppResult<ChatResponse>
        }
        return FakeGrokRepository(mapped.first(), *mapped.drop(1).toTypedArray())
    }

    @Test
    fun `happy path click_text then done stores memory`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = grok(
            """{"status":"continue","action":"click_text","text":"Open Settings"}""",
            """{"status":"done","action":"done","reason":"Settings is open"}""",
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "open settings",
            payload = """{"goal":"Open Settings","settleMs":0,"confirmWithWait":false}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        val output = (result as AgentResult.Success).output!!
        assertTrue(output.contains("OK:"))
        assertTrue(output.contains("Settings is open"))
        assertTrue(output.contains("click_text"))
        assertEquals(listOf("clickByText(Open Settings,exact=false)"), actions.calls)
        assertEquals("Open Settings", context.fakeMemory.store[ScreenActAgent.KEY_LAST_GOAL])
        assertEquals("success", context.fakeMemory.store[ScreenActAgent.KEY_LAST_STATUS])
        assertEquals(output, context.fakeMemory.store[ScreenActAgent.KEY_LAST])
        assertTrue(context.fakeMemory.store[ScreenActAgent.KEY_LAST_STEPS]!!.toInt() >= 2)
    }

    @Test
    fun `multi-step loop executes sequential actions and feeds history to Grok`() = runTest {
        val screen = FakeScreen()
        screen.setDumps(
            "Home\nOpen Settings",
            "Settings\nNetwork & internet\nWi-Fi",
            "Wi-Fi\nOn\nUse Wi-Fi",
        )
        val actions = FakeActions()
        val grok = grok(
            """{"status":"continue","action":"click_text","text":"Open Settings"}""",
            """{"status":"continue","action":"click_text","text":"Wi-Fi"}""",
            """{"status":"done","reason":"Wi-Fi toggle is visible"}""",
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "wifi",
            payload = """{"goal":"Open Settings and show Wi-Fi","maxSteps":8,"settleMs":0,"confirmWithWait":false}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals(
            listOf(
                "clickByText(Open Settings,exact=false)",
                "clickByText(Wi-Fi,exact=false)",
            ),
            actions.calls,
        )
        assertEquals(3, grok.requests.size)
        val secondUser = grok.requests[1].messages.last { it.role.name == "USER" }.content
        assertTrue(secondUser.contains("Steps so far:"))
        assertTrue(secondUser.contains("click_text \"Open Settings\""))
        val output = (result as AgentResult.Success).output!!
        assertTrue(output.contains("1. click_text"))
        assertTrue(output.contains("2. click_text"))
        assertTrue(output.contains("3. done"))
    }

    @Test
    fun `stops with failure when max steps reached`() = runTest {
        val screen = FakeScreen()
        val actions = FakeActions()
        val grok = grok(
            """{"status":"continue","action":"click_text","text":"Next"}""",
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "loop",
            payload = """{"goal":"never finishes","maxSteps":2,"timeoutMs":5000,"settleMs":0,"confirmWithWait":false}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        val reason = (result as AgentResult.Failure).reason
        assertTrue(reason.contains("max steps"))
        assertEquals(2, actions.calls.size)
        assertEquals("max_steps", context.fakeMemory.store[ScreenActAgent.KEY_LAST_STATUS])
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
    fun `fails on invalid json payload`() = runTest {
        val agent = ScreenActAgent(FakeScreen(), FakeActions(), json)
        val task = Task(agentId = ScreenActAgent.ID, title = "act", payload = "{not-json")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("Invalid payload"))
    }

    @Test
    fun `retries on network error from Grok before any UI action`() = runTest {
        val grok = FakeGrokRepository(AppResult.Failure(AppError.Network("timeout")))
        val agent = ScreenActAgent(FakeScreen(), FakeActions(), json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = """{"goal":"tap login","settleMs":0}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
    }

    @Test
    fun `tolerates markdown-wrapped JSON from Grok`() = runTest {
        val actions = FakeActions()
        val grok = grok(
            "```json\n{\"action\":\"global\",\"name\":\"BACK\"}\n```",
            """{"status":"done","reason":"went back"}""",
        )
        val agent = ScreenActAgent(FakeScreen(), actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "back",
            payload = """{"goal":"go back","settleMs":0,"confirmWithWait":false}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("global(BACK)"), actions.calls)
    }

    @Test
    fun `confirmWithWait polls until expected text appears`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Loading…", "Loading…", "Welcome\nHome")
        val actions = FakeActions()
        val grok = grok(
            """{"status":"continue","action":"click_text","text":"Login","expectText":"Welcome"}""",
            """{"status":"done","reason":"logged in"}""",
        )
        val agent = ScreenActAgent(screen, actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "login",
            payload = """{"goal":"log in","settleMs":0,"confirmWithWait":true,"pollMs":10,"timeoutMs":5000}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("clickByText(Login,exact=false)"), actions.calls)
        val output = (result as AgentResult.Success).output!!
        assertTrue(output.contains("wait"))
        assertTrue(screen.dumpCalls >= 2)
    }

    @Test
    fun `vision perception falls back to accessibility text and still works`() = runTest {
        val actions = FakeActions()
        val grok = grok("""{"status":"done","reason":"already on home"}""")
        val agent = ScreenActAgent(FakeScreen(), actions, json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = """{"goal":"be on home","perception":"vision","settleMs":0}""",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue(actions.calls.isEmpty())
        val userMsg = grok.requests[0].messages.last { it.role.name == "USER" }.content
        assertTrue(userMsg.contains("ACCESSIBILITY_TEXT") || userMsg.contains("Accessibility dump"))
        assertTrue(userMsg.contains("Open Settings"))
    }

    @Test
    fun `bare goal text is accepted`() = runTest {
        val grok = grok("""{"status":"done","reason":"already satisfied"}""")
        val agent = ScreenActAgent(FakeScreen(), FakeActions(), json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = "Open Settings and turn Wi-Fi off",
        )
        val context = FakeAgentContext(task, grok = grok)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        val userMsg = grok.requests[0].messages.last { it.role.name == "USER" }.content
        assertTrue(userMsg.contains("Open Settings and turn Wi-Fi off"))
    }

    @Test
    fun `fails when dump returns ERROR`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("ERROR: no active window root")
        val agent = ScreenActAgent(screen, FakeActions(), json)
        val task = Task(
            agentId = ScreenActAgent.ID,
            title = "act",
            payload = """{"goal":"tap login"}""",
        )
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("ERROR:"))
    }
}
