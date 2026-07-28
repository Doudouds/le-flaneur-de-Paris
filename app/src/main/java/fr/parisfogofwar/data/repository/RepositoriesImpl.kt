package fr.parisfogofwar.data.repository

import android.content.Context
import fr.parisfogofwar.data.local.*
import fr.parisfogofwar.domain.model.*
import fr.parisfogofwar.domain.repository.ExplorationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MonumentAsset(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val merimeeReference: String? = null,
    val osmId: String? = null,
)

class RoomMonumentRepository(
    private val context: Context,
    private val dao: MonumentDao,
    private val area: GameArea,
) : MonumentRepository {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun ensureCatalogImported() {
        if (dao.count() > 0) return
        val entries = context.assets.open("paris_monuments.json").bufferedReader().use { reader ->
            json.decodeFromString<List<MonumentAsset>>(reader.readText())
        }
        val entities = entries.mapNotNull { item ->
            if (item.id.isBlank() || item.name.isBlank() || !item.latitude.isFinite() || !item.longitude.isFinite() ||
                !area.contains(LatLng(item.latitude, item.longitude))
            ) return@mapNotNull null
            val cell = LatLng(item.latitude, item.longitude).toGridCellId()
            MonumentEntity(
                id = item.id,
                name = item.name,
                latitude = item.latitude,
                longitude = item.longitude,
                category = item.category?.takeIf(String::isNotBlank),
                description = item.description?.takeIf(String::isNotBlank),
                imageUrl = item.imageUrl?.takeIf(String::isNotBlank),
                merimeeReference = item.merimeeReference?.takeIf(String::isNotBlank),
                osmId = item.osmId?.takeIf(String::isNotBlank),
                gridX = cell.x,
                gridY = cell.y,
            )
        }.distinctBy { it.id }
        dao.insertAll(entities)
    }

    override fun observeRevealedMonuments(): Flow<List<Monument>> = dao.observeRevealed().map { list ->
        list.map { it.toDomain() }
    }

    override fun observeTotalCount(): Flow<Int> = dao.observeCount()

    private fun MonumentEntity.toDomain() = Monument(
        id = id, name = name, latitude = latitude, longitude = longitude,
        category = category, description = description, imageUrl = imageUrl,
        merimeeReference = merimeeReference, osmId = osmId,
        cellId = GridCellId(gridX, gridY),
    )
}

class RoomExplorationRepository(private val dao: DiscoveredCellDao) : ExplorationRepository {
    override fun observeDiscoveredCells(): Flow<Set<GridCellId>> = dao.observeAll().map { rows ->
        rows.mapTo(mutableSetOf()) { GridCellId(it.gridX, it.gridY) }
    }

    override suspend fun discoverCell(cellId: GridCellId, discoveredAtMillis: Long): Boolean =
        dao.insert(DiscoveredCellEntity(cellId.x, cellId.y, discoveredAtMillis)) != -1L

    override suspend fun resetProgress() = dao.deleteAll()

    override fun observeDiscoveryDates(): Flow<List<Long>> = dao.observeAll().map { rows -> rows.map { it.discoveredAtEpochMillis } }
}
