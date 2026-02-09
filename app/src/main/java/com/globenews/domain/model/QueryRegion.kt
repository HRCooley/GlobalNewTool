package com.globenews.domain.model

data class QueryRegion(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Int
)

val GLOBAL_GRID = listOf(
    // Africa
    QueryRegion("West Africa", 10.0, -5.0, 1500),
    QueryRegion("East Africa", -2.0, 35.0, 1500),
    QueryRegion("North Africa", 30.0, 15.0, 1500),
    QueryRegion("Southern Africa", -25.0, 28.0, 1500),
    // Americas
    QueryRegion("Eastern US/Canada", 40.0, -80.0, 1500),
    QueryRegion("Western US", 37.0, -120.0, 1500),
    QueryRegion("Mexico/Central America", 20.0, -100.0, 1500),
    QueryRegion("Brazil", -15.0, -50.0, 2000),
    QueryRegion("Southern South America", -35.0, -65.0, 1500),
    QueryRegion("Northern South America", 5.0, -70.0, 1500),
    // Europe
    QueryRegion("Western Europe", 48.0, 3.0, 1200),
    QueryRegion("Eastern Europe", 50.0, 25.0, 1500),
    QueryRegion("Scandinavia", 60.0, 20.0, 1200),
    QueryRegion("Mediterranean", 40.0, 15.0, 1200),
    QueryRegion("UK/Ireland", 54.0, -2.0, 800),
    // Middle East & Central Asia
    QueryRegion("Middle East", 30.0, 42.0, 1500),
    QueryRegion("Gulf States", 24.0, 52.0, 1000),
    QueryRegion("Central Asia", 42.0, 65.0, 1500),
    // Asia
    QueryRegion("South Asia", 22.0, 78.0, 1500),
    QueryRegion("Southeast Asia", 10.0, 105.0, 1500),
    QueryRegion("East China", 32.0, 118.0, 1500),
    QueryRegion("Japan/Korea", 36.0, 135.0, 1200),
    QueryRegion("Indonesia/Philippines", -2.0, 120.0, 1500),
    // Oceania
    QueryRegion("Australia", -28.0, 135.0, 2000),
    QueryRegion("Pacific", -38.0, 175.0, 1500),
    // Russia
    QueryRegion("Western Russia", 56.0, 40.0, 1500),
    QueryRegion("Siberia", 55.0, 90.0, 2500),
)
