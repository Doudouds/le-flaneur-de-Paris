package fr.parisfogofwar.data.repository

import android.content.Context
import org.json.JSONArray
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
    private val arrondissementResolver = ParisArrondissementResolver(context)

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
        list.map { row -> row.monument.toDomain(row.discoveredAtEpochMillis) }
    }

    override fun observeTotalCount(): Flow<Int> = dao.observeCount()

    override fun observeArrondissementTotals(): Flow<Map<Int?, Int>> = dao.observeAll().map { monuments ->
        monuments.groupingBy { arrondissementResolver.find(it.latitude, it.longitude) }.eachCount()
    }

    override fun observeMonumentCells(): Flow<Set<GridCellId>> = dao.observeMonumentCells().map { rows ->
        rows.mapTo(mutableSetOf()) { GridCellId(it.gridX, it.gridY) }
    }

    private fun MonumentEntity.toDomain(discoveredAtEpochMillis: Long? = null) = Monument(
        id = id, name = name, latitude = latitude, longitude = longitude,
        category = category, description = description, imageUrl = imageUrl,
        merimeeReference = merimeeReference, osmId = osmId,
        cellId = GridCellId(gridX, gridY),
        discoveredAtEpochMillis = discoveredAtEpochMillis,
        arrondissement = arrondissementResolver.find(latitude, longitude),
    )
}

private class ParisArrondissementResolver(context: Context) {
    private data class Point(val longitude: Double, val latitude: Double)
    private data class Boundary(val number: Int, val ring: List<Point>)

    private val boundaries: List<Boundary> by lazy {
        val root = context.assets.open("paris_arrondissements.geojson").bufferedReader().use { reader ->
            org.json.JSONObject(reader.readText())
        }
        val features = root.getJSONArray("features")
        buildList(features.length()) {
            for (index in 0 until features.length()) {
                val feature = features.getJSONObject(index)
                val number = feature.getJSONObject("properties").getInt("c_ar")
                val ring = feature.getJSONObject("geometry")
                    .getJSONArray("coordinates")
                    .getJSONArray(0)
                    .toPointList()
                if (ring.size >= 3) add(Boundary(number, ring))
            }
        }
    }

    fun find(latitude: Double, longitude: Double): Int? =
        boundaries.firstOrNull { contains(it.ring, longitude, latitude) }?.number

    private fun JSONArray.toPointList(): List<Point> = buildList(length()) {
        for (index in 0 until length()) {
            val coordinate = getJSONArray(index)
            add(Point(coordinate.getDouble(0), coordinate.getDouble(1)))
        }
    }

    private fun contains(ring: List<Point>, longitude: Double, latitude: Double): Boolean {
        var inside = false
        var previous = ring.last()
        for (current in ring) {
            val crossesLatitude = (current.latitude > latitude) != (previous.latitude > latitude)
            if (crossesLatitude) {
                val intersectionLongitude = (previous.longitude - current.longitude) *
                    (latitude - current.latitude) / (previous.latitude - current.latitude) + current.longitude
                if (longitude < intersectionLongitude) inside = !inside
            }
            previous = current
        }
        return inside
    }
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
