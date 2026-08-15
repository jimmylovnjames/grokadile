package com.grokadile.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.grokadile.domain.agent.AppCatalogProvider
import com.grokadile.domain.agent.LaunchableApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveAppCatalogProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppCatalogProvider {

    override fun listLaunchable(limit: Int): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved
            .map { info ->
                LaunchableApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .take(limit.coerceAtLeast(1))
    }

    override fun find(query: String, limit: Int): List<LaunchableApp> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        return listLaunchable(200)
            .filter {
                it.label.lowercase().contains(lower) ||
                    it.packageName.lowercase().contains(lower)
            }
            .sortedBy { score(it, lower) }
            .take(limit.coerceAtLeast(1))
    }

    override fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun score(app: LaunchableApp, lower: String): Int = when {
        app.label.lowercase() == lower -> 0
        app.packageName.lowercase() == lower -> 1
        app.label.lowercase().startsWith(lower) -> 2
        else -> 3
    }
}
