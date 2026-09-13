package fr.parisfogofwar.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.parisfogofwar.domain.repository.LocationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import fr.parisfogofwar.domain.usecase.CellDiscoveryResult
import fr.parisfogofwar.domain.usecase.DiscoverCurrentCellUseCase
import fr.parisfogofwar.notifications.DiscoveryNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service() {
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var monumentRepository: MonumentRepository
    @Inject lateinit var discoverCurrentCell: DiscoverCurrentCellUseCase
    @Inject lateinit var notificationHelper: DiscoveryNotificationHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                TRACKING_NOTIFICATION_ID,
                notificationHelper.buildTrackingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (trackingJob == null) {
            trackingJob = serviceScope.launch {
                runCatching { monumentRepository.ensureCatalogImported() }
                launch { notifyNewMonuments() }
                locationRepository.locations()
                    .catch { stopSelf() }
                    .collect { update ->
                        val location = update.filtered
                        if (location == null || !update.safeForFogOfWar) return@collect
                        when (discoverCurrentCell(location)) {
                            is CellDiscoveryResult.NewlyDiscovered,
                            CellDiscoveryResult.AccuracyTooLow,
                            CellDiscoveryResult.OutsideGameArea,
                            CellDiscoveryResult.InvalidLocation,
                            is CellDiscoveryResult.AlreadyDiscovered -> Unit
                        }
                    }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun notifyNewMonuments() {
        var previousIds: Set<String>? = null
        monumentRepository.observeRevealedMonuments().collect { monuments ->
            previousIds?.let { previous ->
                monuments.filter { it.id !in previous }.forEach(notificationHelper::notifyNewMonument)
            }
            previousIds = monuments.mapTo(mutableSetOf()) { it.id }
        }
    }

    override fun onDestroy() {
        trackingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TRACKING_NOTIFICATION_ID = 2001
    }
}
