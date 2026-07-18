package com.grokadile.domain

import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTest {

    @Test
    fun `failed task with attempts remaining can retry`() {
        val task = Task(agentId = "a", title = "t", status = TaskStatus.FAILED, attempts = 1, maxAttempts = 3)
        assertTrue(task.canRetry)
        assertFalse(task.isTerminal)
    }

    @Test
    fun `failed task with no attempts left is terminal`() {
        val task = Task(agentId = "a", title = "t", status = TaskStatus.FAILED, attempts = 3, maxAttempts = 3)
        assertFalse(task.canRetry)
        assertTrue(task.isTerminal)
    }

    @Test
    fun `succeeded task is terminal`() {
        val task = Task(agentId = "a", title = "t", status = TaskStatus.SUCCEEDED)
        assertTrue(task.isTerminal)
    }
}
