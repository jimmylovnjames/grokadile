package com.grokadile.testutil

import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentLogger
import com.grokadile.domain.agent.AgentMemory
import com.grokadile.domain.agent.AppCatalogProvider
import com.grokadile.domain.agent.ClipboardProvider
import com.grokadile.domain.agent.DeviceHealth
import com.grokadile.domain.agent.DeviceHealthProvider
import com.grokadile.domain.agent.LaunchableApp
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatResponse
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.GrokRepository
import com.grokadile.domain.repository.TaskCounts
import com.grokadile.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [AgentMemory] for tests. */
class FakeAgentMemory : AgentMemory {
    val store = linkedMapOf<String, String>()
    override suspend fun get(key: String): String? = store[key]
    override suspend fun put(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
    override suspend fun keys(): List<String> = store.keys.toList()
}

object NoopAgentLogger : AgentLogger {
    override fun d(message: String) = Unit
    override fun i(message: String) = Unit
    override fun w(message: String, t: Throwable?) = Unit
    override fun e(message: String, t: Throwable?) = Unit
}

class FakeGrokRepository(
    private val response: AppResult<ChatResponse>,
) : GrokRepository {
    val requests = mutableListOf<ChatRequest>()
    override suspend fun chat(request: ChatRequest): AppResult<ChatResponse> {
        requests += request
        return response
    }
}

class FakeClipboardProvider(
    initial: String? = null,
) : ClipboardProvider {
    var contents: String? = initial
    override fun getText(): String? = contents
    override fun setText(text: String) { contents = text }
    override fun clear() { contents = null }
}

class FakeAppCatalogProvider(
    val apps: MutableList<LaunchableApp> = mutableListOf(
        LaunchableApp("com.google.android.apps.maps", "Maps"),
        LaunchableApp("com.android.settings", "Settings"),
        LaunchableApp("com.whatsapp", "WhatsApp"),
    ),
) : AppCatalogProvider {
    val launched = mutableListOf<String>()
    var failLaunch: Boolean = false

    override fun listLaunchable(limit: Int): List<LaunchableApp> = apps.take(limit)

    override fun find(query: String, limit: Int): List<LaunchableApp> {
        val q = query.lowercase()
        return apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }.take(limit)
    }

    override fun launch(packageName: String): Boolean {
        if (failLaunch) return false
        val exists = apps.any { it.packageName == packageName }
        if (exists) launched += packageName
        return exists
    }
}

class FakeDeviceHealthProvider(
    var snap: DeviceHealth = DeviceHealth(
        batteryPercent = 77,
        charging = true,
        networkConnected = true,
        networkType = "wifi",
        freeStorageBytes = 2_000_000_000L,
        totalStorageBytes = 8_000_000_000L,
        deviceId = "android-test",
        label = "Pixel Test",
        sdk = 35,
        release = "15",
    ),
) : DeviceHealthProvider {
    override fun snapshot(): DeviceHealth = snap
}

class FakeTaskRepository : TaskRepository {
    val items = mutableListOf<Task>()
    private val flow = MutableStateFlow<List<Task>>(emptyList())

    private fun publish() {
        flow.value = items.toList()
    }

    override fun observeAll(): Flow<List<Task>> = flow
    override fun observeByStatus(status: TaskStatus): Flow<List<Task>> =
        flow.map { list -> list.filter { it.status == status } }
    override fun observeCounts(): Flow<TaskCounts> = flow.map { list ->
        TaskCounts(
            pending = list.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.RETRY_SCHEDULED },
            running = list.count { it.status == TaskStatus.RUNNING },
            succeeded = list.count { it.status == TaskStatus.SUCCEEDED },
            failed = list.count { it.status == TaskStatus.FAILED },
        )
    }

    override suspend fun getById(id: String): Task? = items.find { it.id == id }
    override suspend fun listByStatus(status: TaskStatus, limit: Int): List<Task> =
        items.filter { it.status == status }.take(limit)
    override suspend fun upsert(task: Task) {
        items.removeAll { it.id == task.id }
        items += task
        publish()
    }
    override suspend fun delete(id: String) {
        items.removeAll { it.id == id }
        publish()
    }
    override suspend fun clearTerminal() {
        items.removeAll { it.isTerminal }
        publish()
    }
    override suspend fun claimNext(now: Long): Task? = null
    override suspend fun requeueOrphans() = Unit
}

/** Minimal [AgentContext] for exercising agents in pure JVM tests. */
class FakeAgentContext(
    override val task: Task,
    override val grok: GrokRepository = FakeGrokRepository(
        AppResult.Success(ChatResponse(content = "ok", model = "grok-test")),
    ),
    val fakeMemory: FakeAgentMemory = FakeAgentMemory(),
) : AgentContext {
    val enqueued = mutableListOf<Task>()
    override val agentId: String = task.agentId
    override val logger: AgentLogger = NoopAgentLogger
    override val memory: AgentMemory = fakeMemory
    override val isActive: Boolean = true
    override suspend fun enqueue(task: Task): String {
        enqueued += task
        return task.id
    }
}
