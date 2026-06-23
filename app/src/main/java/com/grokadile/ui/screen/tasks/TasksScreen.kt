package com.grokadile.ui.screen.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.ui.component.StatusChip
import com.grokadile.ui.theme.GrokAmber
import com.grokadile.ui.theme.GrokGreen
import com.grokadile.ui.theme.GrokRed

@Composable
fun TasksScreen(viewModel: TasksViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${tasks.size} task(s)", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::clearCompleted) { Text("Clear completed") }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onRetry = { viewModel.retry(task) },
                    onDelete = { viewModel.delete(task) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(task: Task, onRetry: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(task.status.name, statusColor(task.status))
            }
            Text(
                "agent: ${task.agentId} · attempt ${task.attempts}/${task.maxAttempts}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            task.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.SUCCEEDED -> GrokGreen
    TaskStatus.RUNNING -> GrokGreen
    TaskStatus.FAILED -> GrokRed
    TaskStatus.CANCELLED -> GrokRed
    TaskStatus.PENDING, TaskStatus.RETRY_SCHEDULED -> GrokAmber
}
