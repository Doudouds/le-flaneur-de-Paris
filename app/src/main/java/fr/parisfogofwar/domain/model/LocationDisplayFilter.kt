package fr.parisfogofwar.domain.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps noisy or implausible fixes from moving the location indicator.
 *
 * This is deliberately separate from cell discovery: discovery must continue
 * to receive every raw location so its existing validation remains unchanged.
 */
class LocationDisplayFilter {
    private var lastAcceptedLocation: DomainLocation? = null
    private var displayedLocation: LatLng? = null

    fun accept(location: DomainLocation, nowMillis: Long = System.currentTimeMillis()): LatLng? {
        if (!location.coordinates.isFinite() ||
            !location.accuracyMeters.isFinite() ||
            location.accuracyMeters < 0f ||
            location.accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS
        ) return null

        val ageMillis = nowMillis - location.timestampMillis
        if (ageMillis > MAX_DISPLAYED_LOCATION_AGE_MILLIS || ageMillis < -30_000L) return null

        val previous = lastAcceptedLocation
        if (previous != null) {
            val elapsedSeconds = (location.timestampMillis - previous.timestampMillis) / 1_000.0
            if (elapsedSeconds <= 0.0) return null

            val distanceMeters = distanceBetween(previous.coordinates, location.coordinates)
            val allowedDistanceMeters = max(
                MIN_JUMP_DISTANCE_METERS,
                elapsedSeconds * MAX_REASONABLE_SPEED_METERS_PER_SECOND +
                    previous.accuracyMeters + location.accuracyMeters,
            )
            if (distanceMeters > allowedDistanceMeters) return null
        }

        lastAcceptedLocation = location
        val previousDisplayed = displayedLocation
        val nextDisplayed = when {
            previousDisplayed == null -> location.coordinates
            else -> {
                val movementMeters = distanceBetween(previousDisplayed, location.coordinates)
                val smoothingFactor = when {
                    movementMeters < 20.0 -> 0.25
                    movementMeters < 100.0 -> 0.6
                    else -> 1.0
                }
                interpolate(previousDisplayed, location.coordinates, smoothingFactor)
            }
        }
        displayedLocation = nextDisplayed
        return nextDisplayed
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
        const val MAX_DISPLAYED_LOCATION_AGE_MILLIS = 30_000L
        const val MIN_JUMP_DISTANCE_METERS = 150.0
        const val MAX_REASONABLE_SPEED_METERS_PER_SECOND = 25.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
