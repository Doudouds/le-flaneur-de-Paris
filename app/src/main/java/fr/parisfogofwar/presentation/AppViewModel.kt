package fr.parisfogofwar.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.domain.repository.LocationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import fr.parisfogofwar.domain.usecase.*
import fr.parisfogofwar.data.image.MonumentImageResolver
import fr.parisfogofwar.location.BackgroundLocationController
import fr.parisfogofwar.map.OfflineMapRepository
import fr.parisfogofwar.notifications.DiscoveryNotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LocationPermissionState { Unknown, Granted, Denied }
enum class LocationPrecision { Unknown, Approximate, Precise }

data class AppUiState(
    val isLoading: Boolean = true,
    val permissionState: LocationPermissionState = LocationPermissionState.Unknown,
    val locationPrecision: LocationPrecision = LocationPrecision.Unknown,
    val backgroundTrackingEnabled: Boolean = false,
    val currentLocation: LatLng? = null,
    val lastLocationUpdate: LocationUpdate? = null,
    val showLocationDebug: Boolean = false,
    val discoveredCells: Set<GridCellId> = emptySet(),
    val allMonumentCells: Set<GridCellId> = emptySet(),
    val revealedMonuments: List<Monument> = emptyList(),
    val arrondissementTotals: Map<Int?, Int> = emptyMap(),
    val statistics: ExplorationStatistics = ExplorationStatistics(),
    val message: String? = null,
    val focusLocation: LatLng? = null,
    val openMonumentId: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val monumentRepository: MonumentRepository,
    private val locationRepository: LocationRepository,
    observeCells: ObserveDiscoveredCellsUseCase,
    observeMonuments: ObserveRevealedMonumentsUseCase,
    observeStatistics: ObserveExplorationStatisticsUseCase,
    private val discoverCurrentCell: DiscoverCurrentCellUseCase,
    private val resetProgressUseCase: ResetProgressUseCase,
    private val offlineMapRepository: OfflineMapRepository,
    private val monumentImageResolver: MonumentImageResolver,
    private val discoveryNotificationHelper: DiscoveryNotificationHelper,
    private val backgroundLocationController: BackgroundLocationController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AppUiState(backgroundTrackingEnabled = backgroundLocationController.isEnabled()),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val offlineMapState: StateFlow<fr.parisfogofwar.map.OfflineMapState> = offlineMapRepository.state
    private var locationJob: Job? = null
    private var observedMonumentIds: Set<String>? = null
    private val imageLookupsStarted = mutableSetOf<String>()
    init {
        backgroundLocationController.startIfEnabled()
        // Restore the fog immediately, independently of catalog import and statistics.
        viewModelScope.launch {
            observeCells().distinctUntilChanged().collect { cells ->
                _uiState.update { it.copy(discoveredCells = cells) }
            }
        }
        viewModelScope.launch {
            runCatching { monumentRepository.ensureCatalogImported() }
                .onFailure { _uiState.update { it.copy(isLoading = false, message = "Impossible de charger le catalogue local.") } }
            combine(
                observeMonuments(),
                observeStatistics(),
                monumentRepository.observeMonumentCells(),
                monumentRepository.observeArrondissementTotals(),
            ) { monuments, stats, monumentCells, arrondissementTotals ->
                Quadruple(monuments, stats, monumentCells, arrondissementTotals)
            }.collect { (monuments, stats, monumentCells, arrondissementTotals) ->
                val currentMonumentIds = monuments.mapTo(mutableSetOf()) { it.id }
                observedMonumentIds?.let { previousIds ->
                    monuments.filter { it.id !in previousIds }.forEach(discoveryNotificationHelper::notifyNewMonument)
                }
                observedMonumentIds = currentMonumentIds
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allMonumentCells = monumentCells,
                        revealedMonuments = monuments,
                        arrondissementTotals = arrondissementTotals,
                        statistics = stats,
                    )
                }
            }
        }
    }

    fun setPermissionState(state: LocationPermissionState, precision: LocationPrecision = LocationPrecision.Unknown) {
        _uiState.update { it.copy(permissionState = state, locationPrecision = precision) }
        if (state == LocationPermissionState.Granted) {
            startLocationUpdates()
        } else {
            locationJob?.cancel()
            locationJob = null
        }
    }

    fun startLocationUpdates() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            locationRepository.locations().collect { update ->
                _uiState.update { it.copy(lastLocationUpdate = update) }
                val location = update.filtered ?: return@collect
                if (update.safeForFogOfWar) {
                    when (val result = discoverCurrentCell(location)) {
                        is CellDiscoveryResult.NewlyDiscovered -> Unit
                        // Ignore imprecise fixes silently. They must not block the user
                        // with a repeated warning while the phone is refining its fix.
                        CellDiscoveryResult.AccuracyTooLow -> Unit
                        CellDiscoveryResult.OutsideGameArea -> showMessage("Vous êtes en dehors de la zone d’exploration.")
                        CellDiscoveryResult.InvalidLocation, is CellDiscoveryResult.AlreadyDiscovered -> Unit
                    }
                }
                _uiState.update { it.copy(currentLocation = location.coordinates) }
            }
        }
    }

    fun setBackgroundTrackingEnabled(enabled: Boolean) {
        if (enabled && !backgroundLocationController.hasLocationPermission()) {
            showMessage("Autorisez d'abord la localisation pour activer le suivi.")
            return
        }
        backgroundLocationController.setEnabled(enabled)
        _uiState.update { it.copy(backgroundTrackingEnabled = enabled) }
    }

    fun toggleBackgroundTrackingFromMap() {
        val enabled = !_uiState.value.backgroundTrackingEnabled
        val previousState = _uiState.value.backgroundTrackingEnabled
        setBackgroundTrackingEnabled(enabled)
        if (_uiState.value.backgroundTrackingEnabled != previousState) {
            showMessage(if (enabled) "Suivi en arrière-plan actif" else "Suivi en arrière-plan inactif")
        }
    }

    fun simulateDiscovery(location: LatLng) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            discoverCurrentCell(DomainLocation(location, 1f, System.currentTimeMillis()))
        }
    }

    fun resetProgress() {
        viewModelScope.launch {
            runCatching { resetProgressUseCase() }
                .onFailure { showMessage("Impossible de supprimer la progression.") }
        }
    }

    fun downloadOfflineMap() = offlineMapRepository.downloadParis()
    fun deleteOfflineMap() = offlineMapRepository.deleteParis()

    fun clearMessage() { _uiState.update { it.copy(message = null) } }

    fun setLocationDebugVisible(visible: Boolean) {
        if (!BuildConfig.DEBUG) return
        _uiState.update { it.copy(showLocationDebug = visible) }
    }

    fun focusMonument(monument: Monument) {
        _uiState.update { it.copy(focusLocation = LatLng(monument.latitude, monument.longitude)) }
    }

    fun openMonument(monument: Monument) {
        _uiState.update {
            it.copy(
                focusLocation = LatLng(monument.latitude, monument.longitude),
                openMonumentId = monument.id,
            )
        }
    }

    fun clearOpenMonument() { _uiState.update { it.copy(openMonumentId = null) } }

    fun ensureMonumentImage(monument: Monument) {
        if (monument.imageLicense != null || !imageLookupsStarted.add(monument.id)) return
        viewModelScope.launch {
            runCatching { monumentImageResolver.ensureImage(monument) }
        }
    }

    fun clearFocusLocation() { _uiState.update { it.copy(focusLocation = null) } }

    private fun showMessage(message: String) { _uiState.update { it.copy(message = message) } }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
