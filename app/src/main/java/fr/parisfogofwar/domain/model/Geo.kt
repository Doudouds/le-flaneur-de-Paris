package fr.parisfogofwar.domain.model

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

const val GRID_CELL_SIZE_METERS = 200.0
// A phone can commonly report 50–100 m accuracy in dense urban areas or indoors.
// The exploration cells are 200 m wide, so 100 m is a reasonable compromise.
const val MAX_ACCEPTED_ACCURACY_METERS = 100f
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
        location.latitude in minLatitude..maxLatitude &&
            location.longitude in minLongitude..maxLongitude &&
            parisBoundaryContains(location)

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

// Simplified from the official commune boundary of Paris (INSEE code 75056).
// It is used for gameplay filtering; the map renderer uses the more detailed
// GeoJSON boundary stored in assets/paris_boundary.geojson.
private val parisBoundary = listOf(
    LatLng(48.901485, 2.351984), LatLng(48.901931, 2.385582), LatLng(48.894722, 2.397609),
    LatLng(48.883821, 2.400107), LatLng(48.876443, 2.412453), LatLng(48.864978, 2.413976),
    LatLng(48.855178, 2.415286), LatLng(48.849371, 2.416326), LatLng(48.840488, 2.414248),
    LatLng(48.833779, 2.412587), LatLng(48.835398, 2.421016), LatLng(48.838891, 2.421062),
    LatLng(48.842498, 2.419357), LatLng(48.841886, 2.424535), LatLng(48.840893, 2.437184),
    LatLng(48.845964, 2.440758), LatLng(48.844677, 2.449527), LatLng(48.842938, 2.461008),
    LatLng(48.836788, 2.469662), LatLng(48.834120, 2.469197), LatLng(48.831018, 2.465273),
    LatLng(48.827623, 2.464662), LatLng(48.817365, 2.459115), LatLng(48.817930, 2.448935),
    LatLng(48.819187, 2.437345), LatLng(48.824191, 2.424654), LatLng(48.825087, 2.410270),
    LatLng(48.828791, 2.399775), LatLng(48.823729, 2.385226), LatLng(48.818433, 2.370643),
    LatLng(48.815943, 2.356232), LatLng(48.816102, 2.347178), LatLng(48.816655, 2.335581),
    LatLng(48.820629, 2.321661), LatLng(48.825126, 2.301315), LatLng(48.830291, 2.284719),
    LatLng(48.828039, 2.272928), LatLng(48.827969, 2.267626), LatLng(48.833011, 2.269931),
    LatLng(48.834419, 2.258503), LatLng(48.845730, 2.249867), LatLng(48.850435, 2.237379),
    LatLng(48.855088, 2.224269), LatLng(48.865380, 2.228377), LatLng(48.870523, 2.235576),
    LatLng(48.875904, 2.245157), LatLng(48.880387, 2.258467), LatLng(48.877968, 2.277487),
    LatLng(48.882971, 2.281014), LatLng(48.889456, 2.291634), LatLng(48.895090, 2.305715),
    LatLng(48.900460, 2.319887), LatLng(48.901208, 2.337938),
)

private fun parisBoundaryContains(location: LatLng): Boolean {
    var inside = false
    var previous = parisBoundary.last()
    for (current in parisBoundary) {
        val crossesLatitude = (current.latitude > location.latitude) != (previous.latitude > location.latitude)
        if (crossesLatitude) {
            val crossingLongitude = previous.longitude +
                (current.longitude - previous.longitude) *
                (location.latitude - previous.latitude) /
                (current.latitude - previous.latitude)
            if (location.longitude < crossingLongitude) inside = !inside
        }
        previous = current
    }
    return inside
}

/**
 * A location as reported by Android, kept separate from the filtered position
 * used by the game and the map.
 */
data class DomainLocation(
    val coordinates: LatLng,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long? = null,
    val provider: String? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
)

data class Monument(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String?,
    val description: String?,
    val descriptionSource: String? = null,
    val historicalDates: String? = null,
    val historicalPeriod: String? = null,
    val protectionDates: String? = null,
    val popHistoricalDescription: String? = null,
    val interestScore: Int? = null,
    val interestPercentile: Int? = null,
    val stars: Int? = null,
    val imageUrl: String?,
    val imagePageUrl: String?,
    val imageAuthor: String?,
    val imageLicense: String?,
    val imageLicenseUrl: String?,
    val imageSource: String?,
    val merimeeReference: String?,
    val osmId: String?,
    val cellId: GridCellId,
    val discoveredAtEpochMillis: Long? = null,
    val arrondissement: Int? = null,
    val wikipediaUrl: String? = null,
)

data class ExplorationStatistics(
    val discoveredCells: Int = 0,
    val totalCells: Long = 0,
    val revealedMonuments: Int = 0,
    val totalMonuments: Int = 0,
    val totalMonumentsByStars: Map<Int, Int> = emptyMap(),
    val firstDiscoveryMillis: Long? = null,
    val lastDiscoveryMillis: Long? = null,
) {
    val percentage: Float get() = if (totalCells == 0L) 0f else discoveredCells * 100f / totalCells
}
