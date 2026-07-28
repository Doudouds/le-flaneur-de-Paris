package fr.parisfogofwar.domain.repository

import fr.parisfogofwar.domain.model.DomainLocation
import fr.parisfogofwar.domain.model.GridCellId
import fr.parisfogofwar.domain.model.Monument
import kotlinx.coroutines.flow.Flow

interface MonumentRepository {
    suspend fun ensureCatalogImported()
    fun observeRevealedMonuments(): Flow<List<Monument>>
    fun observeTotalCount(): Flow<Int>
}

interface ExplorationRepository {
    fun observeDiscoveredCells(): Flow<Set<GridCellId>>
    suspend fun discoverCell(cellId: GridCellId, discoveredAtMillis: Long): Boolean
    suspend fun resetProgress()
    fun observeDiscoveryDates(): Flow<List<Long>>
}

interface LocationRepository {
    fun locations(): Flow<DomainLocation>
}
