package fr.parisfogofwar.domain.usecase

import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.domain.repository.ExplorationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ObserveDiscoveredCellsUseCase(private val repository: ExplorationRepository) {
    operator fun invoke(): Flow<Set<GridCellId>> = repository.observeDiscoveredCells()
}

sealed interface CellDiscoveryResult {
    data class NewlyDiscovered(val cellId: GridCellId) : CellDiscoveryResult
    data class AlreadyDiscovered(val cellId: GridCellId) : CellDiscoveryResult
    data object OutsideGameArea : CellDiscoveryResult
    data object AccuracyTooLow : CellDiscoveryResult
    data object InvalidLocation : CellDiscoveryResult
}

class DiscoverCurrentCellUseCase(
    private val repository: ExplorationRepository,
    private val area: GameArea,
) {
    suspend operator fun invoke(location: DomainLocation): CellDiscoveryResult {
        val ageMillis = System.currentTimeMillis() - location.timestampMillis
        if (!location.coordinates.latitude.isFinite() || !location.coordinates.longitude.isFinite() ||
            location.timestampMillis <= 0L || ageMillis > MAX_LOCATION_AGE_MILLIS || ageMillis < -30_000L
        ) return CellDiscoveryResult.InvalidLocation
        if (location.accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) return CellDiscoveryResult.AccuracyTooLow
        if (!area.contains(location.coordinates)) return CellDiscoveryResult.OutsideGameArea
        val cellId = location.coordinates.toGridCellId()
        return if (repository.discoverCell(cellId, location.timestampMillis)) {
            CellDiscoveryResult.NewlyDiscovered(cellId)
        } else {
            CellDiscoveryResult.AlreadyDiscovered(cellId)
        }
    }
}

class ObserveRevealedMonumentsUseCase(private val repository: MonumentRepository) {
    operator fun invoke(): Flow<List<Monument>> = repository.observeRevealedMonuments()
}

class ObserveExplorationStatisticsUseCase(
    private val area: GameArea,
    private val explorationRepository: ExplorationRepository,
    private val monumentRepository: MonumentRepository,
) {
    operator fun invoke(): Flow<ExplorationStatistics> = combine(
        explorationRepository.observeDiscoveredCells(),
        explorationRepository.observeDiscoveryDates(),
        monumentRepository.observeRevealedMonuments(),
        monumentRepository.observeTotalCount(),
        monumentRepository.observeCatalogMonuments(),
    ) { cells, dates, revealed, total, catalog ->
        ExplorationStatistics(
            discoveredCells = cells.size,
            totalCells = area.totalCellCount(),
            revealedMonuments = revealed.size,
            totalMonuments = total,
            totalMonumentsByStars = catalog
                .mapNotNull { monument -> monument.stars?.takeIf { it in 1..3 } }
                .groupingBy { it }
                .eachCount(),
            firstDiscoveryMillis = dates.minOrNull(),
            lastDiscoveryMillis = dates.maxOrNull(),
        )
    }
}

class ResetProgressUseCase(private val repository: ExplorationRepository) {
    suspend operator fun invoke() = repository.resetProgress()
}
