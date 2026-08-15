package com.grokadile.agent

import com.grokadile.agent.builtin.AppLaunchAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.LaunchableApp
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import com.grokadile.testutil.FakeAppCatalogProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchAgentTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val catalog = FakeAppCatalogProvider()
    private val agent = AppLaunchAgent(catalog, json)

    @Test
    fun `launch by unique label`() = runTest {
        val task = Task(
            agentId = AppLaunchAgent.ID,
            title = "open",
            payload = """{"mode":"launch","query":"Maps"}""",
        )
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("com.google.android.apps.maps"), catalog.launched)
    }

    @Test
    fun `launch by package`() = runTest {
        val task = Task(
            agentId = AppLaunchAgent.ID,
            title = "open",
            payload = """{"mode":"launch","packageName":"com.android.settings"}""",
        )
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertEquals("com.android.settings", catalog.launched.single())
    }

    @Test
    fun `ambiguous query fails without launching`() = runTest {
        catalog.apps += LaunchableApp("com.example.maps.lite", "Lite Maps")
        val task = Task(
            agentId = AppLaunchAgent.ID,
            title = "open",
            payload = """{"query":"Maps"}""",
        )
        val result = agent.execute(task, FakeAgentContext(task))
        // exact label "Maps" still wins
        assertTrue(result is AgentResult.Success)
        assertEquals("com.google.android.apps.maps", catalog.launched.single())
    }

    @Test
    fun `unknown app fails`() = runTest {
        val task = Task(agentId = AppLaunchAgent.ID, title = "o", payload = """{"query":"NotAnApp"}""")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Failure)
        assertTrue(catalog.launched.isEmpty())
    }

    @Test
    fun `list includes labels`() = runTest {
        val task = Task(agentId = AppLaunchAgent.ID, title = "l", payload = """{"mode":"list"}""")
        val result = agent.execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("WhatsApp"))
    }

    @Test
    fun `bare text launches`() = runTest {
        val task = Task(agentId = AppLaunchAgent.ID, title = "o", payload = "Settings")
        assertTrue(agent.execute(task, FakeAgentContext(task)) is AgentResult.Success)
        assertEquals("com.android.settings", catalog.launched.single())
    }
}
