package com.grokadile.agent

import com.grokadile.agent.builtin.NotificationListenerAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.NotificationContentProvider
import com.grokadile.domain.agent.NotificationSnapshot
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerAgentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val samples = listOf(
        NotificationSnapshot(
            key = "com.whatsapp:1",
            packageName = "com.whatsapp",
            title = "Alice",
            text = "Hey, are you free?",
            postTime = 1_000L,
        ),
        NotificationSnapshot(
            key = "com.bank:42",
            packageName = "com.example.bank",
            title = "Security code",
            text = "Your code is 482913",
            postTime = 2_000L,
        ),
        NotificationSnapshot(
            key = "com.android.systemui:9",
            packageName = "com.android.systemui",
            title = "Battery",
            text = "15% remaining",
            postTime = 3_000L,
            isOngoing = true,
        ),
    )

    private fun agent(available: Boolean = true, data: List<NotificationSnapshot> = samples) =
        NotificationListenerAgent(FakeNotificationProvider(available, data), json)

    @Test
    fun `list returns compact dump when available`() = runTest {
        val task = Task(
            agentId = NotificationListenerAgent.ID,
            title = "list",
            payload = """{"mode":"list","limit":10}""",
        )
        val ctx = FakeAgentContext(task)
        val result = agent().execute(task, ctx)

        assertTrue(result is AgentResult.Success)
        val out = (result as AgentResult.Success).output!!
        assertTrue(out.contains("whatsapp"))
        assertTrue(out.contains("482913"))
        assertEquals("3", ctx.fakeMemory.store[NotificationListenerAgent.KEY_LAST_COUNT])
    }

    @Test
    fun `match filters by package and text`() = runTest {
        val task = Task(
            agentId = NotificationListenerAgent.ID,
            title = "match",
            payload = """{"mode":"match","packageFilter":"bank","textContains":"code"}""",
        )
        val ctx = FakeAgentContext(task)
        val result = agent().execute(task, ctx)

        assertTrue(result is AgentResult.Success)
        val out = (result as AgentResult.Success).output!!
        assertTrue(out.contains("482913"))
        assertFalse(out.contains("whatsapp"))
    }

    @Test
    fun `unavailable service yields retry`() = runTest {
        val task = Task(
            agentId = NotificationListenerAgent.ID,
            title = "list",
            payload = """{"mode":"list"}""",
        )
        val ctx = FakeAgentContext(task)
        val result = agent(available = false).execute(task, ctx)
        assertTrue(result is AgentResult.Retry)
    }

    @Test
    fun `register_rule and list_rules round-trip`() = runTest {
        val reg = Task(
            agentId = NotificationListenerAgent.ID,
            title = "reg",
            payload = """
                {
                  "mode": "register_rule",
                  "ruleId": "otp",
                  "packageFilter": "bank",
                  "textContains": "code",
                  "targetAgentId": "grok.chat",
                  "targetTitle": "OTP",
                  "targetPriority": "HIGH"
                }
            """.trimIndent(),
        )
        val ctx = FakeAgentContext(reg)
        val a = agent()
        val r1 = a.execute(reg, ctx)
        assertTrue(r1 is AgentResult.Success)
        assertTrue((r1 as AgentResult.Success).output!!.contains("registered rule 'otp'"))

        val list = Task(
            agentId = NotificationListenerAgent.ID,
            title = "rules",
            payload = """{"mode":"list_rules"}""",
        )
        val r2 = a.execute(list, ctx)
        assertTrue(r2 is AgentResult.Success)
        assertTrue((r2 as AgentResult.Success).output!!.contains("otp → grok.chat"))
    }

    @Test
    fun `poll_and_react enqueues target and dedupes`() = runTest {
        val a = agent()
        val ctx = FakeAgentContext(
            Task(agentId = NotificationListenerAgent.ID, title = "seed", payload = "{}"),
        )

        a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "reg",
                payload = """
                    {
                      "mode":"register_rule",
                      "ruleId":"otp",
                      "packageFilter":"bank",
                      "textContains":"code",
                      "targetAgentId":"echo",
                      "targetTitle":"OTP echo",
                      "targetPayload":"{\"prompt\":\"extract otp\"}"
                    }
                """.trimIndent(),
            ),
            ctx,
        )

        val r1 = a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "poll",
                payload = """{"mode":"poll_and_react"}""",
            ),
            ctx,
        )
        assertTrue(r1 is AgentResult.Success)
        assertTrue((r1 as AgentResult.Success).output!!.contains("1 rule(s) fired"))
        assertEquals(1, ctx.enqueued.size)
        assertEquals("echo", ctx.enqueued[0].agentId)
        assertEquals("OTP echo", ctx.enqueued[0].title)

        val before = ctx.enqueued.size
        val r2 = a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "poll2",
                payload = """{"mode":"poll_and_react"}""",
            ),
            ctx,
        )
        assertTrue(r2 is AgentResult.Success)
        assertTrue((r2 as AgentResult.Success).output!!.contains("0 rule(s) fired"))
        assertEquals(before, ctx.enqueued.size)
    }

    @Test
    fun `clear_rules empties the book`() = runTest {
        val a = agent()
        val ctx = FakeAgentContext(
            Task(agentId = NotificationListenerAgent.ID, title = "x", payload = "{}"),
        )
        a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "reg",
                payload = """{"mode":"register_rule","ruleId":"a","targetAgentId":"echo"}""",
            ),
            ctx,
        )
        val cleared = a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "clr",
                payload = """{"mode":"clear_rules"}""",
            ),
            ctx,
        )
        assertTrue(cleared is AgentResult.Success)
        val listed = a.execute(
            Task(
                agentId = NotificationListenerAgent.ID,
                title = "list",
                payload = """{"mode":"list_rules"}""",
            ),
            ctx,
        )
        assertTrue((listed as AgentResult.Success).output!!.contains("no reaction rules"))
    }

    @Test
    fun `unknown mode fails`() = runTest {
        val task = Task(
            agentId = NotificationListenerAgent.ID,
            title = "bad",
            payload = """{"mode":"explode"}""",
        )
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Failure)
    }

    @Test
    fun `snapshot matches helper`() {
        val snap = samples[1]
        assertTrue(snap.matches("bank", null, "code"))
        assertFalse(snap.matches("whatsapp", null, null))
        assertTrue(snap.matches(null, "Security", null))
    }
}

private class FakeNotificationProvider(
    private val available: Boolean,
    private val data: List<NotificationSnapshot>,
) : NotificationContentProvider {
    override fun isAvailable(): Boolean = available
    override fun activeNotifications(limit: Int) = data.take(limit)
    override fun recentNotifications(limit: Int) = data.take(limit)
    override fun findMatching(
        packageFilter: String?,
        titleContains: String?,
        textContains: String?,
        activeOnly: Boolean,
        limit: Int,
    ) = data.filter { it.matches(packageFilter, titleContains, textContains) }.take(limit)
}
