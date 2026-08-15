package com.grokadile.domain.agent

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/** Launcher-visible apps. Implemented with PackageManager QUERY for MAIN/LAUNCHER. */
interface AppCatalogProvider {
    fun listLaunchable(limit: Int = 80): List<LaunchableApp>
    fun find(query: String, limit: Int = 10): List<LaunchableApp>
    fun launch(packageName: String): Boolean
}
