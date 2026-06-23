package com.grokadile.data

import com.grokadile.data.local.mapper.toDomain
import com.grokadile.data.local.mapper.toEntity
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `task survives entity round trip`() {
        val task = Task(
            agentId = "echo",
            title = "round trip",
            payload = "{\"k\":1}",
            status = TaskStatus.RETRY_SCHEDULED,
            priority = TaskPriority.HIGH,
            attempts = 2,
            maxAttempts = 5,
            lastError = "boom",
            resultData = "partial",
        )

        val restored = task.toEntity().toDomain()

        assertEquals(task.id, restored.id)
        assertEquals(task.agentId, restored.agentId)
        assertEquals(task.status, restored.status)
        assertEquals(task.priority, restored.priority)
        assertEquals(task.attempts, restored.attempts)
        assertEquals(task.lastError, restored.lastError)
        assertEquals(task.resultData, restored.resultData)
    }

    @Test
    fun `priority maps to ordered rank`() {
        assertEquals(2, Task(agentId = "a", title = "", priority = TaskPriority.HIGH).toEntity().priorityRank)
        assertEquals(1, Task(agentId = "a", title = "", priority = TaskPriority.NORMAL).toEntity().priorityRank)
        assertEquals(0, Task(agentId = "a", title = "", priority = TaskPriority.LOW).toEntity().priorityRank)
    }
}
