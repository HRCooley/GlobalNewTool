package com.globenews.presentation.globe

import android.util.Log
import android.webkit.JavascriptInterface
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.ViewBounds
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class GlobeBridge(private val moshi: Moshi) {

    companion object {
        private const val TAG = "GlobeBridge"
    }

    private val _markerTaps = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val markerTaps = _markerTaps.asSharedFlow()

    private val _cameraMoves = MutableSharedFlow<GlobeView>(extraBufferCapacity = 5)
    val cameraMoves = _cameraMoves.asSharedFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    @JavascriptInterface
    fun onMarkerTap(storyId: String) {
        Log.d(TAG, "Marker tapped: $storyId")
        _markerTaps.tryEmit(storyId)
    }

    @JavascriptInterface
    fun onCameraMove(viewJson: String) {
        try {
            val adapter = moshi.adapter(CameraViewDto::class.java)
            val dto = adapter.fromJson(viewJson) ?: return
            val view = GlobeView(
                latitude = dto.lat,
                longitude = dto.lon,
                zoom = dto.zoom,
                bounds = dto.bounds?.let {
                    ViewBounds(
                        north = it.north,
                        south = it.south,
                        east = it.east,
                        west = it.west
                    )
                }
            )
            _cameraMoves.tryEmit(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse camera view: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onReady() {
        Log.d(TAG, "Map is ready")
        _isReady.tryEmit(true)
    }
}

@JsonClass(generateAdapter = true)
data class CameraViewDto(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double,
    @Json(name = "zoom") val zoom: Double,
    @Json(name = "bounds") val bounds: BoundsDto?
)

@JsonClass(generateAdapter = true)
data class BoundsDto(
    @Json(name = "north") val north: Double,
    @Json(name = "south") val south: Double,
    @Json(name = "east") val east: Double,
    @Json(name = "west") val west: Double
)
