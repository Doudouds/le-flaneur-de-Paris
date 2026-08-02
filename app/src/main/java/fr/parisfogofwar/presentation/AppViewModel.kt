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

data class AppUiState(
    val isLoading: Boolean = true,
    val permissionState: LocationPermissionState = LocationPermissionState.Unknown,
    val backgroundTrackingEnabled: Boolean = false,
    val currentLocation: LatLng? = null,
    val discoveredCells: Set<GridCellId> = emptySet(),
    val allMonumentCells: Set<GridCellId> = emptySet(),
    val revealedMonuments: List<Monument> = emptyList(),
    val arrondissementTotals: Map<Int?, Int> = emptyMap(),
    val statistics: ExplorationStatistics = ExplorationStatistics(),
    val message: String? = null,
    val focusLocation: LatLng? = null,
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
    private val locationDisplayFilter = LocationDisplayFilter()

    init {
        backgroundLocationController.startIfEnabled()
        viewModelScope.launch {
            runCatching { monumentRepository.ensureCatalogImported() }
                .onFailure { _uiState.update { it.copy(isLoading = false, message = "Impossible de charger le catalogue local.") } }
            combine(
                observeCells(),
                observeMonuments(),
                observeStatistics(),
                monumentRepository.observeMonumentCells(),
                monumentRepository.observeArrondissementTotals(),
            ) { cells, monuments, stats, monumentCells, arrondissementTotals ->
                Quintuple(cells, monuments, stats, monumentCells, arrondissementTotals)
            }.collect { (cells, monuments, stats, monumentCells, arrondissementTotals) ->
                val currentMonumentIds = monuments.mapTo(mutableSetOf()) { it.id }
                observedMonumentIds?.let { previousIds ->
                    monuments.filter { it.id !in previousIds }.forEach(discoveryNotificationHelper::notifyNewMonument)
                }
                observedMonumentIds = currentMonumentIds
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        discoveredCells = cells,
                        allMonumentCells = monumentCells,
                        revealedMonuments = monuments,
                        arrondissementTotals = arrondissementTotals,
                        statistics = stats,
                    )
                }
            }
        }
    }

    fun setPermissionState(state: LocationPermissionState) {
        _uiState.update { it.copy(permissionState = state) }
        if (state == LocationPermissionState.Granted) startLocationUpdates()
    }

    fun startLocationUpdates() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            locationRepository.locations().collect { location ->
                when (val result = discoverCurrentCell(location)) {
                    is CellDiscoveryResult.NewlyDiscovered -> Unit
                    // Ignore imprecise fixes silently. They must not block the user
                    // with a repeated warning while the phone is refining its fix.
                    CellDiscoveryResult.AccuracyTooLow -> Unit
                    CellDiscoveryResult.OutsideGameArea -> showMessage("Vous êtes en dehors de la zone d’exploration.")
                    CellDiscoveryResult.InvalidLocation, is CellDiscoveryResult.AlreadyDiscovered -> Unit
                }
                locationDisplayFilter.accept(location)?.let { displayedLocation ->
                    _uiState.update { it.copy(currentLocation = displayedLocation) }
                }
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

    fun focusMonument(monument: Monument) {
        _uiState.update { it.copy(focusLocation = LatLng(monument.latitude, monument.longitude)) }
    }

    fun ensureMonumentImage(monument: Monument) {
        if (monument.imageLicense != null || !imageLookupsStarted.add(monument.id)) return
        viewModelScope.launch {
            runCatching { monumentImageResolver.ensureImage(monument) }
        }
    }

    fun clearFocusLocation() { _uiState.update { it.copy(focusLocation = null) } }

    private fun showMessage(message: String) { _uiState.update { it.copy(message = message) } }
}

private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
