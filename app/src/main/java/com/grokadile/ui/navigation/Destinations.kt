package com.grokadile.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom-navigation destinations. Add a screen by adding an entry here. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Home),
    TASKS("tasks", "Tasks", Icons.AutoMirrored.Filled.List),
    LOGS("logs", "Logs", Icons.Filled.Info),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}
