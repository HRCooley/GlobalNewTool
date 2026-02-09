package com.globenews.data.source.geocoding

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeocodingDataSource @Inject constructor(
    private val api: NominatimApi
) {
    companion object {
        private const val TAG = "GeocodingDataSource"
    }

    data class PlaceInfo(
        val name: String,
        val countryCode: String?,
        val admin1: String?
    )

    private var cachedResult: Pair<String, PlaceInfo>? = null

    suspend fun reverseGeocode(lat: Double, lon: Double): PlaceInfo? {
        val cacheKey = "${String.format("%.2f", lat)},${String.format("%.2f", lon)}"
        cachedResult?.let { (key, info) ->
            if (key == cacheKey) return info
        }

        return try {
            val response = api.reverseGeocode(lat, lon)
            val address = response.address ?: return null
            val placeName = address.placeName() ?: return null
            val info = PlaceInfo(
                name = placeName,
                countryCode = address.countryCode?.uppercase(),
                admin1 = address.state
            )
            cachedResult = cacheKey to info
            info
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }
    }
}
