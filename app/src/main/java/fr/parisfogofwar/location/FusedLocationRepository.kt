package fr.parisfogofwar.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
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
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMaxUpdateDelayMillis(20_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location.toDomain())
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toDomain() = DomainLocation(
        coordinates = LatLng(latitude, longitude),
        accuracyMeters = accuracy,
        timestampMillis = time,
    )
}
