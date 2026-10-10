package com.fishmate.ai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.fishmate.ai.core.Log2
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Position for weather and session context.
 *
 * Honours the user's consent level: when they chose "approximate", the
 * coordinates are coarsened *here*, before they leave the device, so the
 * backend never receives a precise fishing spot it was not given permission to
 * hold (docs/PRIVACY.md).
 */
interface LocationProvider {
    suspend fun lastKnown(): Position?
    fun hasPermission(): Boolean
    var precision: LocationPrecision
}

data class Position(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

enum class LocationPrecision {
    PRECISE,
    /** Rounded to ~1 km. */
    APPROXIMATE,
    /** Never report a device position. */
    NONE,
}

class FusedLocationProvider(
    private val context: Context,
) : LocationProvider {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override var precision: LocationPrecision = LocationPrecision.APPROXIMATE

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Position? {
        if (precision == LocationPrecision.NONE) return null
        if (!hasPermission()) return null

        val priority = if (precision == LocationPrecision.PRECISE) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            // Coarse priority also saves battery on a long session.
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val raw = runCatching {
            suspendCancellableCoroutine { continuation ->
                client.getCurrentLocation(priority, null)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener {
                        Log2.d(TAG, "location unavailable: ${it.message}")
                        continuation.resume(null)
                    }
            }
        }.getOrNull() ?: return null

        val position = Position(raw.latitude, raw.longitude, raw.accuracy)
        return when (precision) {
            LocationPrecision.PRECISE -> position
            LocationPrecision.APPROXIMATE -> position.copy(
                // Two decimal places is roughly a kilometre: enough for a
                // weather lookup, not enough to find someone's mark.
                latitude = round2(position.latitude),
                longitude = round2(position.longitude),
            )
            LocationPrecision.NONE -> null
        }
    }

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0

    companion object {
        private const val TAG = "Location"
    }
}

/** Fixed position for tests and Demo Mode. */
class FakeLocationProvider(
    private var position: Position? = Position(38.46, 27.09),
) : LocationProvider {
    override var precision: LocationPrecision = LocationPrecision.APPROXIMATE
    override fun hasPermission(): Boolean = position != null
    override suspend fun lastKnown(): Position? =
        if (precision == LocationPrecision.NONE) null else position

    fun set(value: Position?) {
        position = value
    }
}
