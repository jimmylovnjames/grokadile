package com.grokadile.ui.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.ui.component.SectionTitle
import com.grokadile.ui.component.StatCard
import com.grokadile.ui.component.StatusChip

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Orchestrator", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (state.running) "Running · ${state.activeCount} active" else "Stopped",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.running) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                },
                            )
                        }
                        Switch(
                            checked = state.settings.autonomousEnabled,
                            onCheckedChange = viewModel::setAutonomous,
                        )
                    }
                    if (!state.settings.hasApiKey) {
                        Text(
                            "No Grok API key set — network agents will fail. Add one in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Queued", state.counts.pending.toString(), Modifier.weight(1f))
                StatCard("Running", state.counts.running.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Done", state.counts.succeeded.toString(), Modifier.weight(1f))
                StatCard("Failed", state.counts.failed.toString(), Modifier.weight(1f))
            }
        }

        item {
            Button(onClick = viewModel::runSampleTask, modifier = Modifier.fillMaxWidth()) {
                Text("Enqueue sample task")
            }
        }

        item { SectionTitle("Agents (${state.agents.size})") }
        items(state.agents, key = { it.id }) { agent -> AgentRow(agent) }
    }
}

@Composable
private fun AgentRow(agent: AgentDescriptor) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${agent.name}  ·  v${agent.version}", style = MaterialTheme.typography.titleMedium)
            Text(
                agent.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (agent.capabilities.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    agent.capabilities.forEach { cap ->
                        StatusChip(cap.name, MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
