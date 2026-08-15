package com.grokadile.agent

import com.grokadile.agent.builtin.CronNext
import com.grokadile.agent.builtin.SchedulerAgent
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SchedulerAgentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val agent = SchedulerAgent(json)

    // ── interval schedule ──────────────────────────────────────────────────

    @Test
    fun `interval schedule fires target and re-arms self`() = runTest {
        val payload = """
            {
              "scheduleId": "every-minute",
              "type": "interval",
              "intervalMillis": 60000,
              "targetAgentId": "echo",
              "targetTitle": "tick",
              "targetPayload": "{\"msg\":\"hi\"}",
              "targetPriority": "HIGH",
              "fireCount": 0
            }
        """.trimIndent()

        val task = Task(agentId = SchedulerAgent.ID, title = "sched", payload = payload)
        val context = FakeAgentContext(task)

        val before = System.currentTimeMillis()
        val result = agent.execute(task, context)
        val after = System.currentTimeMillis()

        assertTrue(result is AgentResult.Success)
        assertEquals(2, context.enqueued.size)

        // First enqueued = target
        val target = context.enqueued[0]
        assertEquals("echo", target.agentId)
        assertEquals("tick", target.title)
        assertEquals("{\"msg\":\"hi\"}", target.payload)
        assertEquals(TaskPriority.HIGH, target.priority)

        // Second enqueued = self for next fire
        val self = context.enqueued[1]
        assertEquals(SchedulerAgent.ID, self.agentId)
        assertTrue(self.scheduledAt >= before + 55_000)
        assertTrue(self.scheduledAt <= after + 65_000)
        assertTrue(self.payload.contains("\"fireCount\":1"))

        // Memory updated
        assertNotNull(context.fakeMemory.store["schedule:every-minute"])
    }

    @Test
    fun `interval rejects too-small interval`() = runTest {
        val payload = """
            {"type":"interval","intervalMillis":1000,"targetAgentId":"echo"}
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "bad", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)
        assertTrue(result is AgentResult.Failure)
        assertTrue((result as AgentResult.Failure).reason.contains("intervalMillis"))
        assertTrue(context.enqueued.isEmpty())
    }

    @Test
    fun `maxFires stops further scheduling`() = runTest {
        val payload = """
            {
              "type": "interval",
              "intervalMillis": 60000,
              "targetAgentId": "echo",
              "maxFires": 2,
              "fireCount": 2
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "done", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("stopped"))
        assertTrue(context.enqueued.isEmpty())
    }

    @Test
    fun `missing targetAgentId fails`() = runTest {
        val payload = """{"type":"interval","intervalMillis":60000,"targetAgentId":""}"""
        val task = Task(agentId = SchedulerAgent.ID, title = "bad", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)
        assertTrue(result is AgentResult.Failure)
    }

    @Test
    fun `invalid json payload fails`() = runTest {
        val task = Task(agentId = SchedulerAgent.ID, title = "bad", payload = "not-json")
        val context = FakeAgentContext(task)
        val result = agent.execute(task, context)
        assertTrue(result is AgentResult.Failure)
    }

    // ── cron schedule ──────────────────────────────────────────────────────

    @Test
    fun `cron schedule fires target and re-arms with future scheduledAt`() = runTest {
        // Every day at 09:00 — next fire must be in the future.
        val payload = """
            {
              "scheduleId": "morning",
              "type": "cron",
              "expression": "0 9 * * *",
              "targetAgentId": "grok.chat",
              "targetTitle": "Morning brief",
              "targetPayload": "{\"prompt\":\"Good morning\"}"
            }
        """.trimIndent()

        val task = Task(agentId = SchedulerAgent.ID, title = "cron", payload = payload)
        val context = FakeAgentContext(task)

        val before = System.currentTimeMillis()
        val result = agent.execute(task, context)

        assertTrue("result=$result", result is AgentResult.Success)
        assertEquals(2, context.enqueued.size)

        val target = context.enqueued[0]
        assertEquals("grok.chat", target.agentId)
        assertEquals("Morning brief", target.title)

        val self = context.enqueued[1]
        assertEquals(SchedulerAgent.ID, self.agentId)
        assertTrue(
            "next scheduledAt (${self.scheduledAt}) should be after now ($before)",
            self.scheduledAt > before,
        )
        // Next 09:00 is at most ~24h away
        assertTrue(self.scheduledAt - before <= 25 * 60 * 60 * 1000L)
    }

    @Test
    fun `bad cron expression fails cleanly`() = runTest {
        val payload = """
            {"type":"cron","expression":"not a cron","targetAgentId":"echo"}
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "bad-cron", payload = payload)
        val context = FakeAgentContext(task)

        val result = agent.execute(task, context)
        assertTrue(result is AgentResult.Failure)
        assertTrue(context.enqueued.isEmpty())
    }

    // ── CronNext pure logic ────────────────────────────────────────────────

    @Test
    fun `cron every 15 minutes matches expected slots`() {
        val tz = TimeZone.getTimeZone("UTC")
        // Start just after 10:00
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 15, 10, 0, 1)
            set(Calendar.MILLISECOND, 0)
        }
        val next = CronNext.compute("*/15 * * * *", cal.timeInMillis, tz)
        assertNotNull(next)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next!! }
        assertEquals(10, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, nextCal.get(Calendar.MINUTE))
    }

    @Test
    fun `cron daily at 9am`() {
        val tz = TimeZone.getTimeZone("UTC")
        // 2026-08-15 10:30 → next is 2026-08-16 09:00
        val after = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 15, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val next = CronNext.compute("0 9 * * *", after, tz)
        assertNotNull(next)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next!! }
        assertEquals(16, nextCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(Calendar.MINUTE))
    }

    @Test
    fun `cron weekdays only (Mon-Fri)`() {
        val tz = TimeZone.getTimeZone("UTC")
        // 2026-08-15 is a Saturday → next Mon-Fri 09:00 is Monday 17th
        val after = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 15, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val next = CronNext.compute("0 9 * * 1-5", after, tz)
        assertNotNull(next)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next!! }
        assertEquals(Calendar.MONDAY, nextCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(17, nextCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, nextCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `cron list of hours`() {
        val tz = TimeZone.getTimeZone("UTC")
        val after = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 15, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val next = CronNext.compute("0 9,14,18 * * *", after, tz)
        assertNotNull(next)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next!! }
        assertEquals(14, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(Calendar.MINUTE))
    }

    @Test
    fun `cron rejects wrong field count`() {
        try {
            CronNext.compute("* * *", System.currentTimeMillis())
            fail("expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("5 fields"))
        }
    }

    @Test
    fun `cron field range with step`() {
        val tz = TimeZone.getTimeZone("UTC")
        // 9-17/2 → 9,11,13,15,17
        val after = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 15, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val next = CronNext.compute("0 9-17/2 * * *", after, tz)
        assertNotNull(next)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next!! }
        assertEquals(11, nextCal.get(Calendar.HOUR_OF_DAY))
    }
}
