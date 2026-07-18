package com.grokadile.ui.screen.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grokadile.agent.AgentController
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val controller: AgentController,
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = taskRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(task: Task) {
        viewModelScope.launch {
            controller.enqueue(
                task.copy(
                    status = TaskStatus.PENDING,
                    attempts = 0,
                    scheduledAt = System.currentTimeMillis(),
                    lastError = null,
                ),
            )
        }
    }

    fun delete(task: Task) {
        viewModelScope.launch { taskRepository.delete(task.id) }
    }

    fun clearCompleted() {
        viewModelScope.launch { taskRepository.clearTerminal() }
    }
}
