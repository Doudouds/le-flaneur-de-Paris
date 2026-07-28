package fr.parisfogofwar

import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.domain.repository.ExplorationRepository
import fr.parisfogofwar.domain.usecase.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GeoTest {
    @Test fun parisCoordinatesRoundTripWithinTolerance() {
        val original = LatLng(48.8566, 2.3522)
        val roundTrip = original.toWebMercator().toWgs84()
        assertEquals(original.latitude, roundTrip.latitude, 1e-8)
        assertEquals(original.longitude, roundTrip.longitude, 1e-8)
    }

    @Test fun cellIdIsStableAndNearbyPointsShareCell() {
        val id = LatLng(48.8566, 2.3522).toGridCellId()
        assertEquals(id, GridCellId.parse(id.toString()))
        assertEquals(id, LatLng(48.85665, 2.3522).toGridCellId())
    }

    @Test fun pointsAcrossBoundaryUseDifferentCells() {
        val base = GridCellId(33700, 62400).toWebMercatorBounds()
        val left = MercatorPoint(base.minX + 1, base.minY + 1).toWgs84()
        val right = MercatorPoint(base.maxX + 1, base.minY + 1).toWgs84()
        assertNotEquals(left.toGridCellId(), right.toGridCellId())
    }

    @Test fun invalidAndOutsideLocationsAreRejected() = runTest {
        val repo = FakeExplorationRepository()
        val useCase = DiscoverCurrentCellUseCase(repo, GameArea())
        val now = System.currentTimeMillis()
        assertEquals(CellDiscoveryResult.AccuracyTooLow, useCase(DomainLocation(LatLng(48.85, 2.35), 41f, now)))
        assertEquals(CellDiscoveryResult.OutsideGameArea, useCase(DomainLocation(LatLng(49.0, 2.35), 1f, now)))
        assertEquals(CellDiscoveryResult.InvalidLocation, useCase(DomainLocation(LatLng(Double.NaN, 2.35), 1f, now)))
    }

    @Test fun discoveryDoesNotChangeFirstDate() = runTest {
        val repo = FakeExplorationRepository()
        val useCase = DiscoverCurrentCellUseCase(repo, GameArea())
        val location = DomainLocation(LatLng(48.8566, 2.3522), 1f, System.currentTimeMillis())
        assertTrue(useCase(location) is CellDiscoveryResult.NewlyDiscovered)
        assertTrue(useCase(location.copy(timestampMillis = System.currentTimeMillis())) is CellDiscoveryResult.AlreadyDiscovered)
        assertEquals(1, repo.dates.value.size)
    }
}

private class FakeExplorationRepository : ExplorationRepository {
    val cells = MutableStateFlow<Set<GridCellId>>(emptySet())
    val dates = MutableStateFlow<List<Long>>(emptyList())
    override fun observeDiscoveredCells(): Flow<Set<GridCellId>> = cells
    override suspend fun discoverCell(cellId: GridCellId, discoveredAtMillis: Long): Boolean {
        if (cells.value.contains(cellId)) return false
        cells.value = cells.value + cellId
        dates.value = dates.value + discoveredAtMillis
        return true
    }
    override suspend fun resetProgress() { cells.value = emptySet(); dates.value = emptyList() }
    override fun observeDiscoveryDates(): Flow<List<Long>> = dates
}
