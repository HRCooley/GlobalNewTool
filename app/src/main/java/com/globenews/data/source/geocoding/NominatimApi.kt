package com.globenews.data.source.geocoding

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface NominatimApi {
    @GET("reverse")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("zoom") zoom: Int = 10
    ): NominatimResponse
}

@JsonClass(generateAdapter = true)
data class NominatimResponse(
    @Json(name = "address") val address: NominatimAddress?
)

@JsonClass(generateAdapter = true)
data class NominatimAddress(
    @Json(name = "city") val city: String?,
    @Json(name = "town") val town: String?,
    @Json(name = "village") val village: String?,
    @Json(name = "state") val state: String?,
    @Json(name = "country") val country: String?,
    @Json(name = "country_code") val countryCode: String?
)

fun NominatimAddress.placeName(): String? {
    return city ?: town ?: village ?: state
}
