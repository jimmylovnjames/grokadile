package com.grokadile.agent.runtime

import com.grokadile.core.common.DispatcherProvider
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.AgentRegistry
import com.grokadile.domain.model.LogLevel
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.domain.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** Snapshot of the engine for the dashboard / foreground notification. */
data class EngineState(
    val running: Boolean = false,
    val activeCount: Int = 0,
)

/**
 * The orchestration core. Owns a single dispatch loop that atomically claims
 * runnable tasks from the [TaskRepository], runs each on its agent with bounded
 * concurrency, and maps the [AgentResult] back onto task state (including
 * exponential-backoff retries). It holds no Android references, so it is driven
 * by [com.grokadile.service.AgentForegroundService] but unit-testable on its own.
 */
@Singleton
class OrchestrationEngine @Inject constructor(
    private val taskRepository: TaskRepository,
    private val registry: AgentRegistry,
    private val settingsRepository: SettingsRepository,
    private val contextFactory: AgentContextFactory,
    private val logger: GrokLogger,
    private val dispatchers: DispatcherProvider,
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val active = AtomicInteger(0)

    @Volatile private var loopJob: Job? = null
    @Volatile private var engineScope: CoroutineScope? = null

    /** Start the dispatch loop on [scope] (the foreground service's scope). Idempotent. */
    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        engineScope = scope
        loopJob = scope.launch(dispatchers.default) { runLoop() }
        logger.i(TAG, "Orchestration engine started")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        active.set(0)
        _state.value = EngineState(running = false, activeCount = 0)
        logger.i(TAG, "Orchestration engine stopped")
    }

    /** Add work and wake the loop immediately. Returns the task id. */
    suspend fun enqueue(task: Task): String {
        taskRepository.upsert(task)
        wake()
        return task.id
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    private suspend fun runLoop() = coroutineScope {
        taskRepository.requeueOrphans()
        val maxConcurrency = settingsRepository.current().maxConcurrency.coerceIn(1, MAX_PERMITS)
        val slots = Semaphore(maxConcurrency)
        _state.update { it.copy(running = true) }
        try {
            while (currentCoroutineContext().isActive) {
                slots.acquire()
                val task = taskRepository.claimNext()
                if (task == null) {
                    slots.release()
                    // Wait for new work or for a scheduled retry to come due.
                    awaitWake(POLL_INTERVAL_MS)
                    continue
                }
                updateActive(+1)
                launch {
                    try {
                        runTask(task)
                    } finally {
                        slots.release()
                        updateActive(-1)
                        wake() // a slot freed; re-check the queue promptly
                    }
                }
            }
        } finally {
            _state.value = EngineState(running = false, activeCount = 0)
        }
    }

    private suspend fun runTask(task: Task) {
        val agent = registry.get(task.agentId)
        if (agent == null) {
            logger.e(TAG, "No agent registered for id='${task.agentId}'")
            taskRepository.upsert(
                task.copy(
                    status = TaskStatus.FAILED,
                    attempts = task.maxAttempts,
                    lastError = "Unknown agent '${task.agentId}'",
                ),
            )
            return
        }

        val job = currentCoroutineContext()[Job]
        val ctx = contextFactory.create(
            task = task,
            enqueue = ::enqueue,
            isActive = { job?.isActive != false },
        )

        logger.log(LogLevel.INFO, TAG, "▶ ${agent.id}: ${task.title}", agent.id, task.id)
        val result = try {
            agent.execute(task, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AgentResult.Failure(e.message ?: "Agent threw ${e.javaClass.simpleName}", e)
        }
        applyResult(task, result)
    }

    private suspend fun applyResult(task: Task, result: AgentResult) {
        val now = System.currentTimeMillis()
        when (result) {
            is AgentResult.Success -> {
                taskRepository.upsert(
                    task.copy(
                        status = TaskStatus.SUCCEEDED,
                        resultData = result.output,
                        lastError = null,
                        updatedAt = now,
                    ),
                )
                logger.log(LogLevel.INFO, TAG, "✔ ${task.title}", task.agentId, task.id)
            }

            is AgentResult.Retry -> {
                val attempts = task.attempts + 1
                if (attempts >= task.maxAttempts) {
                    taskRepository.upsert(
                        task.copy(
                            status = TaskStatus.FAILED,
                            attempts = attempts,
                            lastError = "Retries exhausted: ${result.reason}",
                            updatedAt = now,
                        ),
                    )
                    logger.w(TAG, "✘ ${task.title} — retries exhausted: ${result.reason}")
                } else {
                    val backoff = result.backoffMillis ?: backoffFor(attempts)
                    taskRepository.upsert(
                        task.copy(
                            status = TaskStatus.RETRY_SCHEDULED,
                            attempts = attempts,
                            scheduledAt = now + backoff,
                            lastError = result.reason,
                            updatedAt = now,
                        ),
                    )
                    logger.w(TAG, "↻ ${task.title} retry #$attempts in ${backoff}ms: ${result.reason}")
                    scheduleWake(backoff)
                }
            }

            is AgentResult.Failure -> {
                taskRepository.upsert(
                    task.copy(
                        status = TaskStatus.FAILED,
                        attempts = task.maxAttempts,
                        lastError = result.reason,
                        updatedAt = now,
                    ),
                )
                logger.e(TAG, "✘ ${task.title}: ${result.reason}", result.cause)
            }
        }
    }

    private suspend fun awaitWake(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) { wakeups.receive() }
    }

    /** Nudge the loop after [delayMillis] so a scheduled retry runs on time. */
    private fun scheduleWake(delayMillis: Long) {
        engineScope?.launch {
            delay(delayMillis)
            wake()
        }
    }

    private fun updateActive(delta: Int) {
        val now = active.addAndGet(delta)
        _state.update { it.copy(activeCount = now, running = true) }
    }

    private fun backoffFor(attempt: Int): Long {
        val exp = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 16)
        val capped = min(exp, MAX_BACKOFF_MS)
        val jitter = (Math.random() * BASE_BACKOFF_MS).toLong()
        return capped + jitter
    }

    companion object {
        private const val TAG = "Orchestrator"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val MAX_PERMITS = 8
    }
}
