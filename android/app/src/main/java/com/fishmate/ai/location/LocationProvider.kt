package com.fishmate.ai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.fishmate.ai.core.Log2
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * The last position this app resolved, with the monotonic clock reading
     * from when it did. Volatile because the tool loop and the session start
     * both read it from whichever thread they happen to be on.
     */
    @Volatile
    private var remembered: Pair<Position, Long>? = null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The angler's position, cheaply.
     *
     * This is on the hot path twice over: the token request carries it, and so
     * does every conditions tool call the model makes. It used to ask
     * `getCurrentLocation` for a brand new fix every time, with no timeout,
     * which is a request the phone answers when the radios feel like it. Two
     * measurements from one device log:
     *
     *  - 17.4 s between tapping Let's Fish and the token request going out;
     *  - 11.6 s between the model asking for conditions and the call being
     *    made — and since the model cannot speak until that answer comes back,
     *    this is what produced "no greeting within 6000ms" and an assistant
     *    that sat silent through its own opening.
     *
     * So the fresh fix is now the last resort rather than the first move, and
     * it is bounded. Staleness costs nothing here: the coordinates are
     * coarsened to about a kilometre anyway, sea state comes off a ~9 km grid,
     * and an angler is not moving between grid cells while they rig a rod.
     */
    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Position? {
        if (precision == LocationPrecision.NONE) return null
        if (!hasPermission()) return null

        // What this app already resolved, a moment ago.
        remembered?.let { (position, at) ->
            if (SystemClock.elapsedRealtime() - at <= CACHE_TTL_MS) return shaped(position)
        }

        // What the platform already has, from whichever app last asked for a
        // fix. Answers immediately or not at all, and on this device it is
        // nearly always populated — the phone has been locating itself for
        // other apps all along.
        val known = awaitLocation { client.lastLocation }
        if (known != null && ageOf(known) <= KNOWN_MAX_AGE_MS) {
            return shaped(remember(known))
        }

        val priority = if (precision == LocationPrecision.PRECISE) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            // Coarse priority also saves battery on a long session.
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        // A real fix, with a deadline. The cancellation token is what makes the
        // deadline mean anything: without it the request outlives the timeout
        // and keeps the radios busy for a caller that has stopped listening.
        val cancellation = CancellationTokenSource()
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            awaitLocation {
                client.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(priority)
                        .setDurationMillis(FIX_TIMEOUT_MS)
                        .build(),
                    cancellation.token,
                )
            }
        }
        cancellation.cancel()
        if (fresh != null) return shaped(remember(fresh))

        // Nothing fresh. An old position beats none at all: the alternative is
        // a session that cannot say where the angler is.
        Log2.w(TAG, "no fix within ${FIX_TIMEOUT_MS}ms; falling back to the last position")
        known?.let { return shaped(remember(it)) }
        return remembered?.let { (position, _) -> shaped(position) }
    }

    private suspend fun awaitLocation(request: () -> Task<Location?>): Location? =
        runCatching {
            suspendCancellableCoroutine { continuation ->
                request()
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener {
                        Log2.d(TAG, "location unavailable: ${it.message}")
                        continuation.resume(null)
                    }
            }
        }.getOrNull()

    /**
     * Monotonic, so a clock change cannot make a fix look fresh. Nanoseconds
     * because the millisecond accessor is API 33 and this ships to API 29.
     */
    private fun ageOf(location: Location): Long =
        SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000L

    private fun remember(location: Location): Position {
        val position = Position(location.latitude, location.longitude, location.accuracy)
        remembered = position to SystemClock.elapsedRealtime()
        return position
    }

    /**
     * Apply the consent level. Done on the way out rather than on the way in,
     * so a position cached while "approximate" is not reported as precise if
     * the angler later grants more.
     */
    private fun shaped(position: Position): Position? = when (precision) {
        LocationPrecision.PRECISE -> position
        LocationPrecision.APPROXIMATE -> position.copy(
            // Two decimal places is roughly a kilometre: enough for a
            // weather lookup, not enough to find someone's mark.
            latitude = round2(position.latitude),
            longitude = round2(position.longitude),
        )
        LocationPrecision.NONE -> null
    }

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0

    companion object {
        private const val TAG = "Location"

        /**
         * How long this app's own resolved position is reused. Covers the
         * burst that opening a session makes — token request, then the model's
         * conditions calls — with one lookup.
         */
        private const val CACHE_TTL_MS = 60_000L

        /** How stale the platform's remembered fix may be before we ask for one. */
        private const val KNOWN_MAX_AGE_MS = 10 * 60_000L

        /**
         * Deadline for a fresh fix. Chosen against what it blocks: the model's
         * first sentence. Two and a half seconds of silence before a greeting
         * is a pause; eleven is a bug report.
         */
        private const val FIX_TIMEOUT_MS = 2_500L
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
