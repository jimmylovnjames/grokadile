package com.grokadile.ui.screen.dashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grokadile.R
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.ui.component.SectionTitle
import com.grokadile.ui.component.StatCard
import com.grokadile.ui.component.StatusChip
import com.grokadile.voice.ChatMessageUi
import com.grokadile.voice.ChatRoleUi
import com.grokadile.voice.VoicePhase

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val voice by viewModel.voiceState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingVoiceEnable by remember { mutableStateOf(false) }
    var pendingPushToTalk by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when {
            pendingVoiceEnable -> {
                pendingVoiceEnable = false
                if (granted) viewModel.setVoiceListening(true)
            }
            pendingPushToTalk -> {
                pendingPushToTalk = false
                if (granted) viewModel.startPushToTalk()
            }
        }
    }

    fun ensureMic(thenEnableVoice: Boolean = false, thenPushToTalk: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            when {
                thenEnableVoice -> viewModel.setVoiceListening(true)
                thenPushToTalk -> viewModel.startPushToTalk()
            }
        } else {
            pendingVoiceEnable = thenEnableVoice
            pendingPushToTalk = thenPushToTalk
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
            VoiceListeningCard(
                enabled = voice.enabled || state.settings.voiceListeningEnabled,
                phase = voice.phase,
                partial = voice.partialTranscript,
                error = voice.lastError,
                onToggle = { enabled ->
                    if (enabled) ensureMic(thenEnableVoice = true)
                    else viewModel.setVoiceListening(false)
                },
            )
        }

        item {
            CommandChatCard(
                messages = messages,
                draft = state.chatDraft,
                onDraftChange = viewModel::setChatDraft,
                onSend = viewModel::sendChat,
                listeningCommand = voice.phase == VoicePhase.LISTENING_COMMAND ||
                    voice.phase == VoicePhase.PROCESSING,
                rms = voice.rms,
                onMicClick = {
                    if (voice.phase == VoicePhase.LISTENING_COMMAND) {
                        viewModel.stopPushToTalk()
                    } else {
                        ensureMic(thenPushToTalk = true)
                    }
                },
            )
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
private fun VoiceListeningCard(
    enabled: Boolean,
    phase: VoicePhase,
    partial: String,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.voice_listening_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when (phase) {
                            VoicePhase.LISTENING_WAKE -> "Say \"Hey Grok\"…"
                            VoicePhase.LISTENING_COMMAND -> "Listening for your command…"
                            VoicePhase.PROCESSING -> "Running command…"
                            VoicePhase.SPEAKING -> "Speaking…"
                            VoicePhase.IDLE -> if (enabled) "Standby" else "Off — tap to enable"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (partial.isNotBlank()) {
                Text(
                    partial,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CommandChatCard(
    messages: List<ChatMessageUi>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    listeningCommand: Boolean,
    rms: Float,
    onMicClick: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    val micScale by animateFloatAsState(
        targetValue = if (listeningCommand) 1f + (rms.coerceIn(0f, 10f) / 40f) else 1f,
        label = "micPulse",
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.command_chat_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Type a command or ask a question. Try: “read the screen”, “tap Login”, “go back”, or “what’s the weather?”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "No commands yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(msg)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.command_chat_hint)) },
                    maxLines = 3,
                )
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size((48 * micScale).dp),
                ) {
                    Icon(
                        imageVector = if (listeningCommand) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.command_chat_mic),
                        tint = if (listeningCommand) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.command_chat_send),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageUi) {
    val isUser = message.role == ChatRoleUi.USER
    val isSystem = message.role == ChatRoleUi.SYSTEM
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = when {
        isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        isSystem -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                when (message.role) {
                    ChatRoleUi.USER -> "You"
                    ChatRoleUi.ASSISTANT -> "Grokadile"
                    ChatRoleUi.SYSTEM -> "System"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
