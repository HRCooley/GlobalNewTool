package com.threadline.domain.model

import java.time.Instant

data class StoryCluster(
    val id: String,
    val representativeTitle: String,
    val representativeSummary: String?,
    val stories: List<NewsStory>,
    val sourceCount: Int,
    val mostRecent: Instant,
    val categories: List<String>,
    val diversityScore: DiversityScore? = null
)

data class DiversityScore(
    val composite: DiversityLevel,
    val regionCount: Int,
    val regionsPresent: List<String>,
    val regionsMissing: List<String>,
    val leanCount: Int,
    val leansPresent: List<String>,
    val leansMissing: List<String>,
    val ownerCount: Int,
    val ownersPresent: List<String>,
    val ownerConcentration: String?
)

enum class DiversityLevel { DIVERSE, MODERATE, CONCENTRATED, SIGNAL }
