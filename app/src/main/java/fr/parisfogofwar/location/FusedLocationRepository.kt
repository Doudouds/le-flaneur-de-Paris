package fr.parisfogofwar.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.parisfogofwar.domain.model.DomainLocation
import fr.parisfogofwar.domain.model.LatLng
import fr.parisfogofwar.domain.repository.LocationRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

class FusedLocationRepository @Inject constructor(
    @ApplicationContext context: Context,
) : LocationRepository {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun locations(): Flow<DomainLocation> = callbackFlow {
        val cancellationTokenSource = CancellationTokenSource()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(5f)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(10_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location.toDomain())
                }
            }
        }
        try {
            // Use the cached position immediately so the map can center without
            // waiting for a new GNSS fix. A fresh high-accuracy fix follows it.
            client.lastLocation.addOnSuccessListener { location ->
                location?.let { trySend(it.toDomain()) }
            }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    location?.let { trySend(it.toDomain()) }
                }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            close()
        }
        awaitClose {
            cancellationTokenSource.cancel()
            client.removeLocationUpdates(callback)
        }
    }

    private fun Location.toDomain() = DomainLocation(
        coordinates = LatLng(latitude, longitude),
        accuracyMeters = accuracy,
        timestampMillis = time,
    )
}
