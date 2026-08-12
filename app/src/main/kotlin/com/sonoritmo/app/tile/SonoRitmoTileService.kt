package com.sonoritmo.app.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.sonoritmo.core.data.repository.AutomationRepository
import com.sonoritmo.core.data.repository.SchedulingWorldRepository
import com.sonoritmo.core.domain.logic.ConflictResolver
import com.sonoritmo.core.domain.model.DesiredState
import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.system.scheduler.SchedulerCoordinator
import com.sonoritmo.core.system.scheduler.Trigger
import com.sonoritmo.app.R
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick-settings tile: pause and resume everything, in one tap.
 *
 * It earns its place twice over. As a feature it is the fastest possible answer to "I'm
 * walking into a cinema". As infrastructure it is the best surviving replacement for
 * `ACTION_USER_PRESENT`: [onStartListening] runs every time the user pulls down the shade
 * with the tile on screen, which is a free, frequent, entirely legitimate moment to check
 * that the device still matches what it should be. See docs/02, decision D-C4.
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class SonoRitmoTileService : TileService() {

    @Inject lateinit var worldRepository: SchedulingWorldRepository

    @Inject lateinit var automationRepository: AutomationRepository

    @Inject lateinit var coordinator: SchedulerCoordinator

    @Inject lateinit var timeSource: TimeSource

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            coordinator.reconcile(Trigger.USER_INTERACTION)
            refreshTile()
        }
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val now = timeSource.now()
            val paused = automationRepository.get().isPausedAt(now)
            if (paused) {
                automationRepository.clearGlobalPause()
            } else {
                automationRepository.setGlobalPause(now.plus(DEFAULT_PAUSE))
            }
            coordinator.reconcile(Trigger.USER_INTERACTION)
            refreshTile()
        }
    }

    private suspend fun refreshTile() {
        val world = worldRepository.load()
        val desired = ConflictResolver.resolve(world, timeSource.now())
        val tile = qsTile ?: return

        when (desired) {
            is DesiredState.Paused -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_paused)
                tile.contentDescription = getString(R.string.tile_paused_description)
            }
            is DesiredState.Active -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = desired.profile.name
                tile.contentDescription =
                    getString(R.string.tile_active_description, desired.profile.name)
            }
            DesiredState.Idle -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_idle)
                tile.contentDescription = getString(R.string.tile_idle_description)
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_sonoritmo)
        tile.updateTile()
    }

    private companion object {
        val DEFAULT_PAUSE: Duration = Duration.ofHours(1)
    }
}
