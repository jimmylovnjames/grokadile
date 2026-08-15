package com.grokadile.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.grokadile.agent.AgentController
import com.grokadile.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick Settings tile that toggles the autonomous orchestrator. */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class AutonomyTileService : TileService() {

    @Inject lateinit var controller: AgentController
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh(settings.current().autonomousEnabled) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val on = settings.current().autonomousEnabled
            if (on) controller.stopAutonomous() else controller.startAutonomous()
            refresh(!on)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (enabled) "Grokadile on" else "Grokadile off"
            updateTile()
        }
    }
}
