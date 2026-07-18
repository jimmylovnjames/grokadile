package com.grokadile.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grokadile.permission.PermissionStatus
import com.grokadile.permission.PermissionType
import com.grokadile.ui.component.SectionTitle
import com.grokadile.ui.theme.GrokGreen
import com.grokadile.ui.theme.GrokRed

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermissions() }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settings.grokModel) {
        if (model.isEmpty()) model = settings.grokModel
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Grok connection") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { viewModel.setGrokModel(model.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save model") }

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = {
                            Text(if (settings.hasApiKey) "API key (saved — overwrite)" else "API key")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.setApiKey(apiKey.trim())
                            apiKey = ""
                        },
                        enabled = apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save API key") }
                }
            }
        }

        item { SectionTitle("Orchestration") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Max concurrency", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                viewModel.setConcurrency(settings.maxConcurrency - 1)
                            }) { Icon(Icons.Filled.Remove, contentDescription = "Decrease") }
                            Text(
                                settings.maxConcurrency.toString(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton(onClick = {
                                viewModel.setConcurrency(settings.maxConcurrency + 1)
                            }) { Icon(Icons.Filled.Add, contentDescription = "Increase") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Run on battery", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = settings.runOnBattery,
                            onCheckedChange = viewModel::setRunOnBattery,
                        )
                    }
                }
            }
        }

        item { SectionTitle("Permissions") }
        items(permissions, key = { it.type.name }) { status ->
            PermissionRow(
                status = status,
                onGrant = {
                    val type = status.type
                    if (type == PermissionType.NOTIFICATIONS &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        settingsLauncher.launch(viewModel.settingsIntentFor(type))
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionRow(status: PermissionStatus, onGrant: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.granted) Icons.Filled.CheckCircle else Icons.Filled.Clear,
                    contentDescription = null,
                    tint = if (status.granted) GrokGreen else GrokRed,
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(label(status.type), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (status.required) "Required" else "Optional",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            if (!status.granted) {
                OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

private fun label(type: PermissionType): String = when (type) {
    PermissionType.NOTIFICATIONS -> "Notifications"
    PermissionType.OVERLAY -> "Display over other apps"
    PermissionType.BATTERY_OPTIMIZATION -> "Ignore battery optimization"
    PermissionType.ACCESSIBILITY -> "Accessibility service"
}
