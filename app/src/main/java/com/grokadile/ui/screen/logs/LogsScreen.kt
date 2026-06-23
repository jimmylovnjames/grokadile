package com.grokadile.ui.screen.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grokadile.domain.model.LogEntry
import com.grokadile.domain.model.LogLevel
import com.grokadile.ui.component.StatusChip
import com.grokadile.ui.theme.GrokAmber
import com.grokadile.ui.theme.GrokGreen
import com.grokadile.ui.theme.GrokRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${logs.size} log line(s)", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::clear) { Text("Clear") }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            items(logs, key = { it.id }) { entry ->
                LogRow(entry, timeFormat.format(Date(entry.timestamp)))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(entry.level.name, levelColor(entry.level))
            Text(
                "$time · ${entry.tag}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        entry.stackTrace?.let {
            Text(
                it.lineSequence().take(6).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> GrokRed
    LogLevel.WARN -> GrokAmber
    LogLevel.INFO -> GrokGreen
    LogLevel.DEBUG, LogLevel.VERBOSE -> GrokGreen
}
