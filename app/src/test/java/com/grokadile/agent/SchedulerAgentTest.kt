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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulerAgentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun agent() = SchedulerAgent(json)

    // ── payload validation ──────────────────────────────────────────────

    @Test
    fun `fails on malformed payload`() = runTest {
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = "not-json")
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Failure)
    }

    @Test
    fun `fails when targetAgentId blank`() = runTest {
        val payload = """{"targetAgentId":"","schedule":{"type":"interval","intervalMillis":60000}}"""
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Failure)
    }

    @Test
    fun `fails when interval too short`() = runTest {
        val payload = """{"targetAgentId":"echo","schedule":{"type":"interval","intervalMillis":1000}}"""
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Failure)
    }

    // ── interval scheduling ─────────────────────────────────────────────

    @Test
    fun `interval enqueues target and self`() = runTest {
        val payload = """
            {
              "targetAgentId": "echo",
              "targetTitle": "ping",
              "targetPayload": "{\"a\":1}",
              "targetPriority": "HIGH",
              "schedule": { "type": "interval", "intervalMillis": 60000 },
              "runCount": 0
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "sched", payload = payload)
        val ctx = FakeAgentContext(task)

        val before = System.currentTimeMillis()
        val result = agent().execute(task, ctx)
        val after = System.currentTimeMillis()

        assertTrue(result is AgentResult.Success)
        assertEquals(2, ctx.enqueued.size)

        val target = ctx.enqueued[0]
        assertEquals("echo", target.agentId)
        assertEquals("ping", target.title)
        assertEquals("{\"a\":1}", target.payload)
        assertEquals(TaskPriority.HIGH, target.priority)
        assertTrue(target.scheduledAt >= before + 60_000 - 50)
        assertTrue(target.scheduledAt <= after + 60_000 + 50)

        val self = ctx.enqueued[1]
        assertEquals(SchedulerAgent.ID, self.agentId)
        assertEquals(target.scheduledAt, self.scheduledAt)
        assertTrue(self.payload.contains("\"runCount\":1"))

        assertEquals("echo", ctx.fakeMemory.store[SchedulerAgent.KEY_LAST_TARGET])
        assertEquals("1", ctx.fakeMemory.store[SchedulerAgent.KEY_RUN_COUNT])
    }

    @Test
    fun `stops when maxRuns reached`() = runTest {
        val payload = """
            {
              "targetAgentId": "echo",
              "schedule": { "type": "interval", "intervalMillis": 60000 },
              "runCount": 5,
              "maxRuns": 5
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val ctx = FakeAgentContext(task)

        val result = agent().execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertEquals(0, ctx.enqueued.size)
        assertTrue((result as AgentResult.Success).output!!.contains("maxRuns"))
    }

    @Test
    fun `stops when disabled`() = runTest {
        val payload = """
            {
              "targetAgentId": "echo",
              "schedule": { "type": "interval", "intervalMillis": 60000 },
              "enabled": false
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val ctx = FakeAgentContext(task)

        val result = agent().execute(task, ctx)
        assertTrue(result is AgentResult.Success)
        assertEquals(0, ctx.enqueued.size)
    }

    // ── cron scheduling ─────────────────────────────────────────────────

    @Test
    fun `cron enqueues target at next matching minute`() = runTest {
        // Every minute — next fire is ~1 min from now
        val payload = """
            {
              "targetAgentId": "screen_reader",
              "targetTitle": "cron dump",
              "schedule": { "type": "cron", "expression": "* * * * *" }
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val ctx = FakeAgentContext(task)

        val before = System.currentTimeMillis()
        val result = agent().execute(task, ctx)

        assertTrue(result is AgentResult.Success)
        assertEquals(2, ctx.enqueued.size)
        val target = ctx.enqueued[0]
        assertEquals("screen_reader", target.agentId)
        // next matching minute should be within ~61 s
        assertTrue(target.scheduledAt > before)
        assertTrue(target.scheduledAt - before <= 61_000)
    }

    @Test
    fun `fails on invalid cron expression`() = runTest {
        val payload = """
            {
              "targetAgentId": "echo",
              "schedule": { "type": "cron", "expression": "not a cron" }
            }
        """.trimIndent()
        val task = Task(agentId = SchedulerAgent.ID, title = "s", payload = payload)
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Failure)
    }

    // ── CronNext pure unit tests ────────────────────────────────────────

    @Test
    fun `parseField star`() {
        val s = CronNext.parseField("*", 0, 5)!!
        assertEquals(setOf(0, 1, 2, 3, 4, 5), s)
    }

    @Test
    fun `parseField step`() {
        val s = CronNext.parseField("*/15", 0, 59)!!
        assertEquals(setOf(0, 15, 30, 45), s)
    }

    @Test
    fun `parseField range`() {
        val s = CronNext.parseField("10-12", 0, 59)!!
        assertEquals(setOf(10, 11, 12), s)
    }

    @Test
    fun `parseField list`() {
        val s = CronNext.parseField("1,3,5", 0, 10)!!
        assertEquals(setOf(1, 3, 5), s)
    }

    @Test
    fun `parseField rejects out of range`() {
        assertNull(CronNext.parseField("99", 0, 59))
        assertNull(CronNext.parseField("5-3", 0, 59))
        assertNull(CronNext.parseField("*/0", 0, 59))
        assertNull(CronNext.parseField("abc", 0, 59))
    }

    @Test
    fun `nextFire every minute returns near future`() {
        val now = System.currentTimeMillis()
        val next = CronNext.nextFire("* * * * *", now)
        assertNotNull(next)
        assertTrue(next!! > now)
        assertTrue(next - now <= 61_000)
    }

    @Test
    fun `nextFire specific hour of day`() {
        // Fixed base: 2026-08-15 10:30 NZST-ish; force UTC for determinism
        val zone = ZoneId.of("UTC")
        val base = ZonedDateTime.of(2026, 8, 15, 10, 30, 0, 0, zone)
            .toInstant().toEpochMilli()

        // Next 09:00 — already past today, so tomorrow 09:00
        val next = CronNext.nextFire("0 9 * * *", base, zone)
        assertNotNull(next)
        val nextZ = java.time.Instant.ofEpochMilli(next!!).atZone(zone)
        assertEquals(9, nextZ.hour)
        assertEquals(0, nextZ.minute)
        assertEquals(16, nextZ.dayOfMonth) // next day
    }

    @Test
    fun `nextFire every 15 minutes`() {
        val zone = ZoneId.of("UTC")
        val base = ZonedDateTime.of(2026, 8, 15, 10, 7, 0, 0, zone)
            .toInstant().toEpochMilli()

        val next = CronNext.nextFire("*/15 * * * *", base, zone)
        assertNotNull(next)
        val nextZ = java.time.Instant.ofEpochMilli(next!!).atZone(zone)
        assertEquals(10, nextZ.hour)
        assertEquals(15, nextZ.minute)
    }

    @Test
    fun `nextFire rejects bad expression`() {
        assertNull(CronNext.nextFire("* * *", System.currentTimeMillis()))
        assertNull(CronNext.nextFire("60 * * * *", System.currentTimeMillis()))
    }
}
