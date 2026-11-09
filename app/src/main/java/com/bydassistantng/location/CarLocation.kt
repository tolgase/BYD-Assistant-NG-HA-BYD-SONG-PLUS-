package com.bydassistantng.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Where the car is, as far as the head unit last knew it. */
data class CarPosition(val lat: Double, val lon: Double, val accuracyM: Float?, val ageMinutes: Long?)

/**
 * Song PLUS edition: where the car is, for the weather ("what's the weather?", the welcome summary).
 *
 * Only the position the head unit already has is used — the last fix of any provider, which the car's own maps keep
 * fresh — so asking costs nothing and never switches GPS on. It needs the coarse location permission, which the driver
 * grants once in Settings. The place is named by Android's geocoder where the head unit has one, else by OpenStreetMap
 * (the same keyless service the dashboard uses), else given as coordinates; one lookup is kept for a few hours.
 */
object CarLocation {
    private const val TAG = "CarLocation"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/reverse"
    private const val AGENT = "byd-assistant-songplus/1.6 (in-car voice assistant)"
    private const val TIMEOUT_MS = 3_500
    private const val CACHE_MS = 6 * 60 * 60_000L

    // A move of about this much (degrees, ~10 km) is a different town: the cached name is not reused.
    private const val SAME_PLACE_DEG = 0.1

    private data class Named(val lat: Double, val lon: Double, val name: String, val atMs: Long)
    @Volatile private var cached: Named? = null

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The freshest position any provider has, or null (no permission, or nothing known yet). */
    // The permission is checked on the first line ([permitted]); lint cannot see through that call.
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): CarPosition? {
        if (!permitted(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best: Location = runCatching {
            manager.allProviders.mapNotNull { p ->
                try {
                    manager.getLastKnownLocation(p)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        val age = (System.currentTimeMillis() - best.time).takeIf { best.time > 0 }?.let { it / 60_000 }
        return CarPosition(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy else null, age)
    }

    /** "Monastir, Tunisia", or the coordinates, or null when the position is not known. */
    suspend fun placeName(context: Context): String? {
        val pos = lastKnown(context) ?: return null
        cached?.let { c ->
            if (System.currentTimeMillis() - c.atMs < CACHE_MS &&
                Math.abs(c.lat - pos.lat) < SAME_PLACE_DEG && Math.abs(c.lon - pos.lon) < SAME_PLACE_DEG
            ) {
                return c.name
            }
        }
        val name = withContext(Dispatchers.IO) { fromGeocoder(context, pos) ?: fromNominatim(pos) }
        if (name != null) cached = Named(pos.lat, pos.lon, name, System.currentTimeMillis())
        return name ?: PlaceName.coordinates(pos.lat, pos.lon)
    }

    @Suppress("DEPRECATION")
    private fun fromGeocoder(context: Context, pos: CarPosition): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            Geocoder(context, Locale.getDefault()).getFromLocation(pos.lat, pos.lon, 1)?.firstOrNull()
                ?.let { PlaceName.fromParts(it.locality, it.adminArea, it.countryName) }
        }
    } catch (e: Exception) {
        AppLogger.log(TAG, "The head unit's geocoder could not name the place: ${e.javaClass.simpleName}")
        null
    }

    private fun fromNominatim(pos: CarPosition): String? = try {
        val url = URL(
            String.format(Locale.ROOT, "%s?format=jsonv2&zoom=10&lat=%.4f&lon=%.4f", NOMINATIM, pos.lat, pos.lon),
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept-Language", Locale.getDefault().language)
            if (c.responseCode !in 200..299) null else PlaceName.fromNominatim(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        AppLogger.log(TAG, "OpenStreetMap could not name the place: ${e.javaClass.simpleName}")
        null
    }
}
