package com.grokadile.agent

import com.grokadile.agent.builtin.ScreenWaitAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWaitAgentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeScreen(
        var available: Boolean = true,
        private val dumps: MutableList<String> = mutableListOf(
            "Loading…",
            "Loading…",
            "Home\nOpen Settings\nDone",
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

    @Test
    fun `waits until text appears`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Loading", "Loading", "Settings page\nNetwork")
        val agent = ScreenWaitAgent(screen, json)
        val payload = """{"mode":"appear","text":"Settings","timeoutMs":5000,"pollMs":10}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("appeared"))
        assertTrue(screen.dumpCalls >= 2)
        assertEquals(
            result.output,
            context.fakeMemory.store[ScreenWaitAgent.KEY_LAST],
        )
    }

    @Test
    fun `waits until text disappears`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Loading… please wait", "Loading… please wait", "Ready")
        val agent = ScreenWaitAgent(screen, json)
        val payload = """{"mode":"disappear","text":"Loading","timeoutMs":5000,"pollMs":10}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait-gone", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("disappeared"))
    }

    @Test
    fun `waits for package`() = runTest {
        val screen = FakeScreen(pkg = "com.example.app")
        val agent = ScreenWaitAgent(screen, json)
        // package already matches → success on first poll
        val payload = """{"mode":"package","packageName":"com.example.app","timeoutMs":2000,"pollMs":10}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait-pkg", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("package"))
    }

    @Test
    fun `retries when accessibility unavailable`() = runTest {
        val screen = FakeScreen(available = false)
        val agent = ScreenWaitAgent(screen, json)
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait", payload = """{"text":"x"}""")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Retry)
        assertTrue((result as AgentResult.Retry).reason.contains("Accessibility"))
    }

    @Test
    fun `fails when text missing for appear`() = runTest {
        val screen = FakeScreen()
        val agent = ScreenWaitAgent(screen, json)
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait", payload = """{"mode":"appear"}""")
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("text"))
    }

    @Test
    fun `times out when text never appears`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Nothing here", "Still nothing")
        val agent = ScreenWaitAgent(screen, json)
        val payload = """{"mode":"appear","text":"Never","timeoutMs":80,"pollMs":20}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "wait", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("Timeout"))
        assertTrue(result.reason.contains("Never"))
    }

    @Test
    fun `exact match requires full line`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Open Settings button")
        val agent = ScreenWaitAgent(screen, json)
        // exact=true should NOT match partial line
        val payload = """{"mode":"appear","text":"Settings","exact":true,"timeoutMs":60,"pollMs":20}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "exact", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("Timeout"))
    }

    @Test
    fun `exact match succeeds on full line`() = runTest {
        val screen = FakeScreen()
        screen.setDumps("Settings")
        val agent = ScreenWaitAgent(screen, json)
        val payload = """{"mode":"appear","text":"Settings","exact":true,"timeoutMs":2000,"pollMs":10}"""
        val task = Task(agentId = ScreenWaitAgent.ID, title = "exact-ok", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)

        assertTrue(result is AgentResult.Success)
    }
}
