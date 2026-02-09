package com.globenews.domain.model

data class GlobeView(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val bounds: ViewBounds?
)

data class ViewBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)
