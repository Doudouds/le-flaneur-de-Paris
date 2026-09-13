package fr.parisfogofwar.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.parisfogofwar.BuildConfig
import fr.parisfogofwar.domain.model.DomainLocation
import fr.parisfogofwar.domain.model.LatLng
import fr.parisfogofwar.domain.model.LocationQualityManager
import fr.parisfogofwar.domain.model.LocationUpdate
import fr.parisfogofwar.domain.repository.LocationRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject

class FusedLocationRepository @Inject constructor(
    @ApplicationContext context: Context,
) : LocationRepository {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sharedLocations = callbackFlow<LocationUpdate> {
        val cancellationTokenSource = CancellationTokenSource()
        val qualityManager = LocationQualityManager()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(750L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(2_000L)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .build()
        fun forward(location: Location) {
            val update = qualityManager.process(
                raw = location.toDomain(),
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            )
            log(update)
            trySend(update)
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::forward)
            }
        }
        try {
            // Only allow a very recent cached fix for initial centering. The
            // quality manager still validates its monotonic timestamp.
            val currentRequest = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(5_000L)
                .build()
            client.getCurrentLocation(currentRequest, cancellationTokenSource.token)
                .addOnSuccessListener { location -> location?.let(::forward) }
                .addOnFailureListener { error ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "getCurrentLocation failed", error)
                }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (error: SecurityException) {
            close(error)
        }
        awaitClose {
            cancellationTokenSource.cancel()
            client.removeLocationUpdates(callback)
        }
    }.shareIn(
        scope = repositoryScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L, replayExpirationMillis = 0L),
        replay = 1,
    )

    @SuppressLint("MissingPermission")
    override fun locations(): Flow<LocationUpdate> = sharedLocations

    private fun Location.toDomain() = DomainLocation(
        coordinates = LatLng(latitude, longitude),
        accuracyMeters = accuracy,
        timestampMillis = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        provider = provider,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        speedAccuracyMetersPerSecond = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        bearingDegrees = if (hasBearing()) bearing else null,
    )

    private fun log(update: LocationUpdate) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "location raw=${update.raw.coordinates.latitude},${update.raw.coordinates.longitude} " +
                "accuracy=${update.raw.accuracyMeters}m provider=${update.raw.provider} " +
                "age=${update.ageMillis}ms speed=${update.raw.speedMetersPerSecond} " +
                "bearing=${update.raw.bearingDegrees} distance=${update.distanceFromPreviousMeters}m " +
                "estimatedSpeed=${update.estimatedSpeedMetersPerSecond} " +
                "decision=${update.decision} quality=${update.quality} " +
                "filtered=${update.filtered?.coordinates}",
        )
    }

    private companion object {
        const val TAG = "FusedLocationRepository"
    }
}
