package com.globenews.domain.model

import java.time.Instant

data class NewsStory(
    val id: String,
    val title: String,
    val summary: String?,
    val url: String,
    val imageUrl: String?,
    val publishedAt: Instant,
    val location: StoryLocation,
    val scope: EditorialScope,
    val category: NewsCategory,
    val sources: List<SourceAttribution>,
    val language: String,
    val sentiment: Float?,
    val isBookmarked: Boolean = false
)

data class StoryLocation(
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
    val countryCode: String?,
    val admin1: String?
)

enum class EditorialScope { INTERNATIONAL, NATIONAL, REGIONAL, LOCAL }

enum class NewsCategory(val displayName: String, val gdeltThemes: List<String>) {
    ALL("All", emptyList()),
    CONFLICT("Conflict", listOf("MILITARY", "ARMED_CONFLICT", "KILL", "TERROR", "PROTEST", "REVOLT")),
    POLITICS("Politics", listOf("ELECTION", "GOVERN", "LEGISLATION", "DIPLOMACY", "SUMMIT", "SANCTION")),
    ECONOMY("Economy", listOf("ECON_", "TRADE", "INFLATION", "BANKRUPTCY", "STOCK", "CURRENCY")),
    ENVIRONMENT("Environment", listOf("ENV_", "CLIMATE", "EARTHQUAKE", "FLOOD", "WILDFIRE", "DROUGHT")),
    HEALTH("Health", listOf("HEALTH_", "PANDEMIC", "EPIDEMIC", "DISEASE", "VACCINE")),
    TECHNOLOGY("Technology", listOf("CYBER", "AI_", "TECH_", "DIGITAL", "HACK")),
    CRIME("Crime", listOf("CRIME", "ARREST", "FRAUD", "DRUG_TRADE", "CORRUPTION")),
    ENERGY("Energy", listOf("ENERGY", "OIL", "GAS", "NUCLEAR_POWER", "RENEWABLE"))
}

data class SourceAttribution(
    val name: String,
    val providerApi: String,
    val retrievedAt: Instant
)
