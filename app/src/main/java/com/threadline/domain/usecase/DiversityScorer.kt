package com.threadline.domain.usecase

import com.threadline.data.source.local.dao.FeedDao
import com.threadline.domain.model.DiversityLevel
import com.threadline.domain.model.DiversityScore
import com.threadline.domain.model.StoryCluster

object DiversityScorer {

    private val ALL_REGIONS = listOf(
        "north_american", "european", "middle_eastern",
        "east_asian", "south_asian", "african", "latin_american"
    )
    private val ALL_LEANS = listOf(
        "establishment", "center_left", "center_right",
        "left_progressive", "right_conservative", "non_aligned"
    )

    suspend fun scoreDiversity(cluster: StoryCluster, feedDao: FeedDao): DiversityScore {
        val feedNames = cluster.stories.map { it.sourceName }.distinct()

        val feeds = feedNames.mapNotNull { name -> feedDao.getByName(name) }

        // Dimension 1: Geographic
        val regions = feeds.mapNotNull { it.region }.distinct()

        // Dimension 2: Political
        val leans = feeds.mapNotNull { it.politicalLean }.distinct()

        // Dimension 3: Ownership
        val owners = feeds.mapNotNull { it.ownerName }.distinct()

        // Owner concentration check
        val ownerCounts = feeds.mapNotNull { it.ownerName }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        val ownerConcentration = if (ownerCounts.size <= 2 && feeds.size > 4) {
            val top = ownerCounts.take(2).joinToString(" and ") { it.key }
            "${feeds.size} sources controlled by $top"
        } else null

        // Composite score
        val composite = when {
            cluster.sourceCount <= 2 -> DiversityLevel.SIGNAL
            regions.size >= 4 && leans.size >= 3 -> DiversityLevel.DIVERSE
            regions.size >= 2 || leans.size >= 2 -> DiversityLevel.MODERATE
            else -> DiversityLevel.CONCENTRATED
        }

        return DiversityScore(
            composite = composite,
            regionCount = regions.size,
            regionsPresent = regions,
            regionsMissing = ALL_REGIONS - regions.toSet(),
            leanCount = leans.size,
            leansPresent = leans,
            leansMissing = ALL_LEANS - leans.toSet(),
            ownerCount = owners.size,
            ownersPresent = owners,
            ownerConcentration = ownerConcentration
        )
    }
}
