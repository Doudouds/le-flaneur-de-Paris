package fr.parisfogofwar.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.parisfogofwar.domain.model.GameArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineMapState(
    val isDownloading: Boolean = false,
    val isAvailable: Boolean = false,
    val progress: Float? = null,
    val errorMessage: String? = null,
)

interface OfflineMapRepository {
    val state: StateFlow<OfflineMapState>
    fun downloadParis()
    fun deleteParis()
}

@Singleton
class MapLibreOfflineMapRepository @Inject constructor(
    @ApplicationContext context: Context,
) : OfflineMapRepository {
    companion object {
        private const val METADATA = "paris-fog-of-war-paris-v1"
        private const val MIN_ZOOM = 10.0
        private const val MAX_ZOOM = 16.0
    }

    private val manager = OfflineManager.getInstance(context)
    private val _state = MutableStateFlow(OfflineMapState())
    override val state: StateFlow<OfflineMapState> = _state.asStateFlow()
    private var parisRegion: OfflineRegion? = null

    init { refresh() }

    override fun downloadParis() {
        _state.value = _state.value.copy(isDownloading = true, errorMessage = null)
        val existing = parisRegion
        if (existing != null) {
            observe(existing)
            existing.setDownloadState(OfflineRegion.STATE_ACTIVE)
            return
        }
        val area = GameArea()
        try {
            val bounds = LatLngBounds.from(area.maxLatitude, area.maxLongitude, area.minLatitude, area.minLongitude)
            val definition = OfflineTilePyramidRegionDefinition(MapConfig.styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, 1f, false)
            manager.createOfflineRegion(definition, METADATA.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    parisRegion = offlineRegion
                    observe(offlineRegion)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    _state.value = OfflineMapState(errorMessage = error)
                }
            })
        } catch (error: IllegalArgumentException) {
            _state.value = OfflineMapState(errorMessage = error.message ?: "Impossible de préparer la carte hors connexion.")
        }
    }

    override fun deleteParis() {
        val region = parisRegion ?: return
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                parisRegion = null
                _state.value = OfflineMapState()
            }

            override fun onError(error: String) {
                _state.value = _state.value.copy(errorMessage = error)
            }
        })
    }

    private fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                parisRegion = offlineRegions?.firstOrNull { String(it.metadata, Charsets.UTF_8) == METADATA }
                parisRegion?.let(::observe)
            }

            override fun onError(error: String) {
                _state.value = _state.value.copy(errorMessage = error)
            }
        })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                val required = status.requiredResourceCount
                val completed = status.completedResourceCount
                val complete = status.isComplete
                _state.value = OfflineMapState(
                    isDownloading = !complete,
                    isAvailable = complete,
                    progress = if (required > 0) (completed.toFloat() / required).coerceIn(0f, 1f) else null,
                )
            }

            override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                _state.value = _state.value.copy(isDownloading = false, errorMessage = error.message)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _state.value = _state.value.copy(isDownloading = false, errorMessage = "La limite de tuiles hors connexion a été atteinte ($limit).")
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: org.maplibre.android.offline.OfflineRegionStatus?) {
                if (status == null) return
                val required = status.requiredResourceCount
                val complete = status.isComplete
                _state.value = _state.value.copy(
                    isDownloading = !complete,
                    isAvailable = complete,
                    progress = if (required > 0) (status.completedResourceCount.toFloat() / required).coerceIn(0f, 1f) else null,
                )
            }

            override fun onError(error: String?) {
                _state.value = _state.value.copy(isDownloading = false, errorMessage = error)
            }
        })
    }
}
