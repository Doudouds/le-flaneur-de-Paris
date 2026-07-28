package fr.parisfogofwar.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.domain.repository.LocationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import fr.parisfogofwar.domain.usecase.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LocationPermissionState { Unknown, Granted, Denied }

data class AppUiState(
    val isLoading: Boolean = true,
    val permissionState: LocationPermissionState = LocationPermissionState.Unknown,
    val currentLocation: LatLng? = null,
    val discoveredCells: Set<GridCellId> = emptySet(),
    val revealedMonuments: List<Monument> = emptyList(),
    val statistics: ExplorationStatistics = ExplorationStatistics(),
    val message: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val monumentRepository: MonumentRepository,
    private val locationRepository: LocationRepository,
    observeCells: ObserveDiscoveredCellsUseCase,
    observeMonuments: ObserveRevealedMonumentsUseCase,
    observeStatistics: ObserveExplorationStatisticsUseCase,
    private val discoverCurrentCell: DiscoverCurrentCellUseCase,
    private val resetProgress: ResetProgressUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { monumentRepository.ensureCatalogImported() }
                .onFailure { _uiState.update { it.copy(isLoading = false, message = "Impossible de charger le catalogue local.") } }
            combine(observeCells(), observeMonuments(), observeStatistics()) { cells, monuments, stats ->
                Triple(cells, monuments, stats)
            }.collect { (cells, monuments, stats) ->
                _uiState.update { it.copy(isLoading = false, discoveredCells = cells, revealedMonuments = monuments, statistics = stats) }
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
                _uiState.update { it.copy(currentLocation = location.coordinates) }
                when (val result = discoverCurrentCell(location)) {
                    is CellDiscoveryResult.NewlyDiscovered -> showMessage("Nouveau secteur découvert !")
                    CellDiscoveryResult.AccuracyTooLow -> showMessage("La précision GPS est insuffisante.")
                    CellDiscoveryResult.OutsideGameArea -> showMessage("Vous êtes en dehors de la zone d’exploration.")
                    CellDiscoveryResult.InvalidLocation, is CellDiscoveryResult.AlreadyDiscovered -> Unit
                }
            }
        }
    }

    fun simulateDiscovery(location: LatLng) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            discoverCurrentCell(DomainLocation(location, 1f, System.currentTimeMillis()))
        }
    }

    fun resetProgress() {
        viewModelScope.launch { resetProgress() }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null) } }

    private fun showMessage(message: String) { _uiState.update { it.copy(message = message) } }
}
