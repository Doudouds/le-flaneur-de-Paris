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
        assertEquals(CellDiscoveryResult.AccuracyTooLow, useCase(DomainLocation(LatLng(48.85, 2.35), 101f, now)))
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

    @Test fun pedestrianWalkAcceptsCoherentPositions() {
        val filter = LocationQualityManager()
        val first = DomainLocation(LatLng(48.8566, 2.3522), 8f, 1_000L, speedMetersPerSecond = 2.5f)
        val next = DomainLocation(LatLng(48.856627, 2.3522), 8f, 2_000L, speedMetersPerSecond = 2.5f)

        assertEquals(LocationDecision.ACCEPTED, filter.process(first, nowMillis = 1_000L).decision)
        val update = filter.process(next, nowMillis = 2_000L)

        assertTrue(update.filtered != null)
        assertTrue(update.decision == LocationDecision.SMOOTHED || update.decision == LocationDecision.ACCEPTED)
        assertTrue(update.safeForFogOfWar)
    }

    @Test fun stationaryGpsDriftDoesNotMoveFilteredPosition() {
        val filter = LocationQualityManager()
        val first = DomainLocation(LatLng(48.8566, 2.3522), 6f, 1_000L, speedMetersPerSecond = 0f)
        val driftEast = DomainLocation(LatLng(48.8566, 2.35223), 6f, 2_000L, speedMetersPerSecond = 0f)
        val driftWest = DomainLocation(LatLng(48.8566, 2.35218), 6f, 3_000L, speedMetersPerSecond = 0f)

        val firstUpdate = filter.process(first, nowMillis = 1_000L)
        val secondUpdate = filter.process(driftEast, nowMillis = 2_000L)
        val thirdUpdate = filter.process(driftWest, nowMillis = 3_000L)

        assertEquals(first.coordinates, firstUpdate.filtered?.coordinates)
        assertEquals(first.coordinates, secondUpdate.filtered?.coordinates)
        assertEquals(first.coordinates, thirdUpdate.filtered?.coordinates)
        assertEquals(LocationDecision.STATIONARY_HOLD, thirdUpdate.decision)
    }

    @Test fun largeOneSecondGpsJumpIsRejected() {
        val filter = LocationQualityManager()
        val first = DomainLocation(LatLng(48.8566, 2.3522), 6f, 1_000L, speedMetersPerSecond = 0f)
        val jump = DomainLocation(LatLng(48.8566, 2.3527), 6f, 2_000L)

        filter.process(first, nowMillis = 1_000L)
        val update = filter.process(jump, nowMillis = 2_000L)

        assertEquals(LocationDecision.REJECTED_ABERRANT, update.decision)
        assertNull(update.filtered)
        assertFalse(update.safeForFogOfWar)
    }

    @Test fun poorAccuracyCanBeDisplayedButCannotRevealFog() {
        val filter = LocationQualityManager()
        val first = DomainLocation(LatLng(48.8566, 2.3522), 8f, 1_000L, speedMetersPerSecond = 1f)
        val poor = DomainLocation(LatLng(48.85668, 2.3522), 80f, 2_000L, speedMetersPerSecond = 1f)

        filter.process(first, nowMillis = 1_000L)
        val update = filter.process(poor, nowMillis = 2_000L)

        assertEquals(LocationQuality.POOR, update.quality)
        assertNotNull(update.filtered)
        assertFalse(update.safeForFogOfWar)
    }

    @Test fun directionChangeIsFollowedWithoutLongStraightLineLag() {
        val filter = LocationQualityManager()
        val first = DomainLocation(LatLng(48.8566, 2.3522), 6f, 1_000L, speedMetersPerSecond = 2.5f)
        val north = DomainLocation(LatLng(48.85663, 2.3522), 6f, 2_000L, speedMetersPerSecond = 2.5f)
        val east = DomainLocation(LatLng(48.85663, 2.35224), 6f, 3_000L, speedMetersPerSecond = 2.5f)

        filter.process(first, nowMillis = 1_000L)
        filter.process(north, nowMillis = 2_000L)
        val turned = filter.process(east, nowMillis = 3_000L)

        assertTrue(turned.filtered!!.coordinates.longitude > north.coordinates.longitude)
        assertTrue(turned.filtered.coordinates.latitude <= east.coordinates.latitude)
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
