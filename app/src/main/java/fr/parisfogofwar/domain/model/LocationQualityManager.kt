package fr.parisfogofwar.domain.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class LocationQuality {
    GOOD,
    FAIR,
    POOR,
}

enum class LocationDecision {
    ACCEPTED,
    SMOOTHED,
    STATIONARY_HOLD,
    REJECTED_INVALID,
    REJECTED_STALE,
    REJECTED_ACCURACY,
    REJECTED_OUT_OF_ORDER,
    REJECTED_ABERRANT,
}

/**
 * One raw Android fix and the decision made by [LocationQualityManager].
 *
 * A rejected update is still returned so DEBUG diagnostics can explain why it
 * did not move the marker or reveal a cell. [filtered] is the only position
 * that should be used by the UI and the exploration game.
 */
data class LocationUpdate(
    val raw: DomainLocation,
    val filtered: DomainLocation?,
    val decision: LocationDecision,
    val quality: LocationQuality,
    val ageMillis: Long,
    val distanceFromPreviousMeters: Double?,
    val estimatedSpeedMetersPerSecond: Double?,
    val safeForFogOfWar: Boolean,
)

/**
 * Small, stateful filter for a pedestrian trace.
 *
 * It deliberately does not perform map matching. A point is first checked for
 * freshness, quality and plausible movement, then moved toward with a light,
 * adaptive exponential filter. When the device appears stationary, small
 * movements inside the uncertainty radius are held at the last stable point.
 */
class LocationQualityManager(
    private val config: Config = Config(),
) {
    data class Config(
        val maxAcceptedAccuracyMeters: Float = 100f,
        val maxMeasurementAgeMillis: Long = 15_000L,
        val maxFutureSkewMillis: Long = 30_000L,
        val maxWalkingSpeedMetersPerSecond: Double = 5.0,
        val maxPlausibleSpeedMetersPerSecond: Double = 25.0,
        val stationarySpeedMetersPerSecond: Double = 0.8,
        val stationaryHoldRadiusMeters: Double = 8.0,
        val fogAccuracyLimitMeters: Float = 35f,
    )

    private var lastAcceptedRaw: DomainLocation? = null
    private var filteredCoordinates: LatLng? = null

    fun process(
        raw: DomainLocation,
        nowMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeNanos: Long? = null,
    ): LocationUpdate {
        val quality = qualityOf(raw.accuracyMeters)
        val ageMillis = measurementAgeMillis(raw, nowMillis, nowElapsedRealtimeNanos)

        if (!raw.coordinates.isFinite() ||
            !raw.accuracyMeters.isFinite() ||
            raw.accuracyMeters < 0f ||
            raw.timestampMillis <= 0L
        ) {
            return rejected(raw, LocationDecision.REJECTED_INVALID, quality, ageMillis)
        }
        if (ageMillis > config.maxMeasurementAgeMillis || ageMillis < -config.maxFutureSkewMillis) {
            return rejected(raw, LocationDecision.REJECTED_STALE, quality, ageMillis)
        }
        if (raw.accuracyMeters > config.maxAcceptedAccuracyMeters) {
            return rejected(raw, LocationDecision.REJECTED_ACCURACY, quality, ageMillis)
        }

        val previous = lastAcceptedRaw
        val distance = previous?.let { distanceBetween(it.coordinates, raw.coordinates) }
        val elapsedSeconds = previous?.let { elapsedSecondsBetween(it, raw) }
        if (previous != null && (elapsedSeconds == null || elapsedSeconds <= 0.0)) {
            return rejected(raw, LocationDecision.REJECTED_OUT_OF_ORDER, quality, ageMillis, distance, null)
        }

        val estimatedSpeed = if (distance != null && elapsedSeconds != null && elapsedSeconds > 0.0) {
            distance / elapsedSeconds
        } else {
            null
        }
        val reliableReportedSpeed = raw.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0f && it <= config.maxPlausibleSpeedMetersPerSecond }
            ?.takeIf { speed ->
                val speedAccuracy = raw.speedAccuracyMetersPerSecond
                speedAccuracy == null ||
                    (speedAccuracy.isFinite() && speedAccuracy >= 0f && speedAccuracy <= max(2f, speed * 0.5f))
            }
            ?.toDouble()
        val movementSpeed = reliableReportedSpeed ?: estimatedSpeed

        if (previous != null && distance != null && elapsedSeconds != null) {
            val noiseAllowance = (previous.accuracyMeters + raw.accuracyMeters)
                .toDouble()
                .coerceAtMost(20.0)
            val speedLimit = max(config.maxWalkingSpeedMetersPerSecond, reliableReportedSpeed ?: 0.0)
            val allowedDistance = elapsedSeconds * speedLimit + noiseAllowance
            val implausibleWithoutReliableSpeed = reliableReportedSpeed == null &&
                estimatedSpeed != null && estimatedSpeed > config.maxPlausibleSpeedMetersPerSecond
            if (implausibleWithoutReliableSpeed || distance > allowedDistance) {
                return rejected(raw, LocationDecision.REJECTED_ABERRANT, quality, ageMillis, distance, estimatedSpeed)
            }
        }

        val previousFiltered = filteredCoordinates
        val nextCoordinates: LatLng
        val decision: LocationDecision
        if (previousFiltered == null) {
            nextCoordinates = raw.coordinates
            decision = LocationDecision.ACCEPTED
        } else {
            val movementFromFiltered = distanceBetween(previousFiltered, raw.coordinates)
            val appearsStationary = movementSpeed != null &&
                movementSpeed <= config.stationarySpeedMetersPerSecond
            val holdRadius = max(config.stationaryHoldRadiusMeters, raw.accuracyMeters * 0.75)
            if (appearsStationary && movementFromFiltered <= holdRadius) {
                nextCoordinates = previousFiltered
                decision = LocationDecision.STATIONARY_HOLD
            } else {
                val factor = smoothingFactor(
                    accuracyMeters = raw.accuracyMeters,
                    movementMeters = movementFromFiltered,
                    speedMetersPerSecond = movementSpeed,
                )
                nextCoordinates = interpolate(previousFiltered, raw.coordinates, factor)
                decision = if (factor >= 0.99) LocationDecision.ACCEPTED else LocationDecision.SMOOTHED
            }
        }

        lastAcceptedRaw = raw
        filteredCoordinates = nextCoordinates
        val filtered = raw.copy(coordinates = nextCoordinates)
        return LocationUpdate(
            raw = raw,
            filtered = filtered,
            decision = decision,
            quality = quality,
            ageMillis = ageMillis,
            distanceFromPreviousMeters = distance,
            estimatedSpeedMetersPerSecond = estimatedSpeed,
            safeForFogOfWar = raw.accuracyMeters <= config.fogAccuracyLimitMeters,
        )
    }

    fun reset() {
        lastAcceptedRaw = null
        filteredCoordinates = null
    }

    private fun rejected(
        raw: DomainLocation,
        decision: LocationDecision,
        quality: LocationQuality,
        ageMillis: Long,
        distance: Double? = null,
        estimatedSpeed: Double? = null,
    ) = LocationUpdate(
        raw = raw,
        filtered = null,
        decision = decision,
        quality = quality,
        ageMillis = ageMillis,
        distanceFromPreviousMeters = distance,
        estimatedSpeedMetersPerSecond = estimatedSpeed,
        safeForFogOfWar = false,
    )

    private fun smoothingFactor(
        accuracyMeters: Float,
        movementMeters: Double,
        speedMetersPerSecond: Double?,
    ): Double {
        if (movementMeters >= 20.0 || (speedMetersPerSecond ?: 0.0) >= 2.5) return 0.85
        return when {
            accuracyMeters <= 10f -> 0.65
            accuracyMeters <= 20f -> 0.50
            accuracyMeters <= 35f -> 0.35
            else -> 0.20
        }
    }

    private fun measurementAgeMillis(
        location: DomainLocation,
        nowMillis: Long,
        nowElapsedRealtimeNanos: Long?,
    ): Long {
        val elapsedNanos = location.elapsedRealtimeNanos
        if (elapsedNanos != null && elapsedNanos > 0L && nowElapsedRealtimeNanos != null && nowElapsedRealtimeNanos >= elapsedNanos) {
            return (nowElapsedRealtimeNanos - elapsedNanos) / NANOS_PER_MILLISECOND
        }
        return nowMillis - location.timestampMillis
    }

    private fun elapsedSecondsBetween(from: DomainLocation, to: DomainLocation): Double? {
        val fromElapsed = from.elapsedRealtimeNanos
        val toElapsed = to.elapsedRealtimeNanos
        if (fromElapsed != null && toElapsed != null) {
            val delta = toElapsed - fromElapsed
            return delta.takeIf { it > 0L }?.toDouble()?.div(1_000_000_000.0)
        }
        return (to.timestampMillis - from.timestampMillis).takeIf { it > 0L }?.toDouble()?.div(1_000.0)
    }

    private fun qualityOf(accuracyMeters: Float): LocationQuality = when {
        accuracyMeters <= 10f -> LocationQuality.GOOD
        accuracyMeters <= 35f -> LocationQuality.FAIR
        else -> LocationQuality.POOR
    }

    private fun LatLng.isFinite(): Boolean = latitude.isFinite() && longitude.isFinite()

    private fun interpolate(from: LatLng, to: LatLng, factor: Double) = LatLng(
        latitude = from.latitude + (to.latitude - from.latitude) * factor,
        longitude = from.longitude + (to.longitude - from.longitude) * factor,
    )

    private fun distanceBetween(from: LatLng, to: LatLng): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(fromLatitude) * cos(toLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
