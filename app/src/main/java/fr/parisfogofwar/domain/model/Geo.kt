package fr.parisfogofwar.domain.model

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

const val GRID_CELL_SIZE_METERS = 100.0
const val MAX_ACCEPTED_ACCURACY_METERS = 40f
const val MAX_LOCATION_AGE_MILLIS = 2 * 60 * 1000L
private const val EARTH_RADIUS_METERS = 6_378_137.0

data class LatLng(val latitude: Double, val longitude: Double)

data class GridCellId(val x: Long, val y: Long) {
    override fun toString(): String = "$x:$y"

    companion object {
        fun parse(value: String): GridCellId? {
            val parts = value.split(':')
            if (parts.size != 2) return null
            return parts[0].toLongOrNull()?.let { x -> parts[1].toLongOrNull()?.let { y -> GridCellId(x, y) } }
        }
    }
}

data class MercatorPoint(val x: Double, val y: Double)
data class MercatorBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

fun LatLng.toWebMercator(): MercatorPoint {
    val latitude = latitude.coerceIn(-85.05112878, 85.05112878)
    val latitudeRadians = Math.toRadians(latitude)
    return MercatorPoint(
        x = EARTH_RADIUS_METERS * Math.toRadians(longitude),
        y = EARTH_RADIUS_METERS * ln(tan(Math.PI / 4.0 + latitudeRadians / 2.0)),
    )
}

fun MercatorPoint.toWgs84(): LatLng {
    val longitude = Math.toDegrees(x / EARTH_RADIUS_METERS)
    val latitude = Math.toDegrees(2.0 * kotlin.math.atan(kotlin.math.exp(y / EARTH_RADIUS_METERS)) - Math.PI / 2.0)
    return LatLng(latitude, longitude)
}

fun LatLng.toGridCellId(cellSizeMeters: Double = GRID_CELL_SIZE_METERS): GridCellId {
    val point = toWebMercator()
    return GridCellId(floor(point.x / cellSizeMeters).toLong(), floor(point.y / cellSizeMeters).toLong())
}

fun GridCellId.toWebMercatorBounds(cellSizeMeters: Double = GRID_CELL_SIZE_METERS): MercatorBounds {
    val minX = x * cellSizeMeters
    val minY = y * cellSizeMeters
    return MercatorBounds(minX, minY, minX + cellSizeMeters, minY + cellSizeMeters)
}

fun MercatorBounds.toWgs84Polygon(): List<LatLng> = listOf(
    MercatorPoint(minX, minY).toWgs84(),
    MercatorPoint(maxX, minY).toWgs84(),
    MercatorPoint(maxX, maxY).toWgs84(),
    MercatorPoint(minX, maxY).toWgs84(),
    MercatorPoint(minX, minY).toWgs84(),
)

data class GameArea(
    val minLatitude: Double = 48.815,
    val maxLatitude: Double = 48.903,
    val minLongitude: Double = 2.224,
    val maxLongitude: Double = 2.470,
) {
    private val minCell: GridCellId = LatLng(minLatitude, minLongitude).toGridCellId()
    private val maxCell: GridCellId = LatLng(maxLatitude, maxLongitude).toGridCellId()

    fun contains(location: LatLng): Boolean =
        location.latitude in minLatitude..maxLatitude && location.longitude in minLongitude..maxLongitude

    fun contains(cellId: GridCellId): Boolean = cellId.x in minCell.x..maxCell.x && cellId.y in minCell.y..maxCell.y

    fun totalCellCount(): Long =
        (maxCell.x - minCell.x + 1).coerceAtLeast(0) * (maxCell.y - minCell.y + 1).coerceAtLeast(0)

    fun cellsForViewport(bounds: MercatorBounds, margin: Long = 1): Sequence<GridCellId> {
        val first = GridCellId(floor(bounds.minX / GRID_CELL_SIZE_METERS).toLong() - margin, floor(bounds.minY / GRID_CELL_SIZE_METERS).toLong() - margin)
        val last = GridCellId(floor(bounds.maxX / GRID_CELL_SIZE_METERS).toLong() + margin, floor(bounds.maxY / GRID_CELL_SIZE_METERS).toLong() + margin)
        val fromX = maxOf(first.x, minCell.x)
        val toX = minOf(last.x, maxCell.x)
        val fromY = maxOf(first.y, minCell.y)
        val toY = minOf(last.y, maxCell.y)
        return sequence {
            for (y in fromY..toY) for (x in fromX..toX) yield(GridCellId(x, y))
        }
    }
}

data class DomainLocation(val coordinates: LatLng, val accuracyMeters: Float, val timestampMillis: Long)

data class Monument(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String?,
    val description: String?,
    val imageUrl: String?,
    val merimeeReference: String?,
    val osmId: String?,
    val cellId: GridCellId,
)

data class ExplorationStatistics(
    val discoveredCells: Int = 0,
    val totalCells: Long = 0,
    val revealedMonuments: Int = 0,
    val totalMonuments: Int = 0,
    val firstDiscoveryMillis: Long? = null,
    val lastDiscoveryMillis: Long? = null,
) {
    val percentage: Float get() = if (totalCells == 0L) 0f else discoveredCells * 100f / totalCells
}
