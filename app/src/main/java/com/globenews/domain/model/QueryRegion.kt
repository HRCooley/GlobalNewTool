package com.globenews.domain.model

data class QueryRegion(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Int,
    val countryCodes: List<String> = emptyList() // FIPS 2-letter codes for GDELT
)

val GLOBAL_GRID = listOf(
    // Africa
    QueryRegion("West Africa", 10.0, -5.0, 1500, listOf("NI", "GH", "SG", "IV")),
    QueryRegion("East Africa", -2.0, 35.0, 1500, listOf("KE", "TZ", "ET", "UG")),
    QueryRegion("North Africa", 30.0, 15.0, 1500, listOf("EG", "MO", "AG", "LY")),
    QueryRegion("Southern Africa", -25.0, 28.0, 1500, listOf("SF", "ZI", "MZ")),
    // Americas
    QueryRegion("Eastern US/Canada", 40.0, -80.0, 1500, listOf("US", "CA")),
    QueryRegion("Western US", 37.0, -120.0, 1500, listOf("US")),
    QueryRegion("Mexico/Central America", 20.0, -100.0, 1500, listOf("MX", "GT", "HO")),
    QueryRegion("Brazil", -15.0, -50.0, 2000, listOf("BR")),
    QueryRegion("Southern South America", -35.0, -65.0, 1500, listOf("AR", "CI", "UY")),
    QueryRegion("Northern South America", 5.0, -70.0, 1500, listOf("CO", "VE", "PE")),
    // Europe
    QueryRegion("Western Europe", 48.0, 3.0, 1200, listOf("FR", "GM", "NL", "BE", "SP")),
    QueryRegion("Eastern Europe", 50.0, 25.0, 1500, listOf("PL", "UP", "HU", "RO")),
    QueryRegion("Scandinavia", 60.0, 20.0, 1200, listOf("SW", "NO", "FI", "DA")),
    QueryRegion("Mediterranean", 40.0, 15.0, 1200, listOf("IT", "GR", "TU")),
    QueryRegion("UK/Ireland", 54.0, -2.0, 800, listOf("UK", "EI")),
    // Middle East & Central Asia
    QueryRegion("Middle East", 30.0, 42.0, 1500, listOf("IZ", "IS", "SY", "LE", "JO", "IR")),
    QueryRegion("Gulf States", 24.0, 52.0, 1000, listOf("SA", "AE", "QA", "KU")),
    QueryRegion("Central Asia", 42.0, 65.0, 1500, listOf("UZ", "KZ", "TX")),
    // Asia
    QueryRegion("South Asia", 22.0, 78.0, 1500, listOf("IN", "PK", "BG", "CE")),
    QueryRegion("Southeast Asia", 10.0, 105.0, 1500, listOf("TH", "VM", "MY")),
    QueryRegion("East China", 32.0, 118.0, 1500, listOf("CH")),
    QueryRegion("Japan/Korea", 36.0, 135.0, 1200, listOf("JA", "KS")),
    QueryRegion("Indonesia/Philippines", -2.0, 120.0, 1500, listOf("ID", "RP")),
    // Oceania
    QueryRegion("Australia", -28.0, 135.0, 2000, listOf("AS", "NZ")),
    QueryRegion("Pacific", -38.0, 175.0, 1500, listOf("NZ")),
    // Russia
    QueryRegion("Western Russia", 56.0, 40.0, 1500, listOf("RS")),
    QueryRegion("Siberia", 55.0, 90.0, 2500, listOf("RS")),
)
