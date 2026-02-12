package com.threadline.domain.usecase

import android.util.Log
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.entity.ManagedFeedEntity
import com.threadline.domain.model.DiversityLevel
import com.threadline.domain.model.DiversityScore
import com.threadline.domain.model.StoryCluster

object DiversityScorer {

    private const val TAG = "DiversityScorer"

    private val ALL_REGIONS = listOf(
        "north_american", "european", "middle_eastern",
        "east_asian", "south_asian", "african", "latin_american"
    )
    private val ALL_LEANS = listOf(
        "center", "establishment", "center_left", "center_right",
        "left_progressive", "right_conservative", "non_aligned"
    )

    private val STRIP_SUFFIXES = Regex(
        "\\s*(World|News|English|International|Global|Online|Digital|\\(.*?\\))\\s*",
        RegexOption.IGNORE_CASE
    )

    private suspend fun findFeedForSource(
        sourceName: String,
        feedDao: FeedDao
    ): ManagedFeedEntity? {
        // 1. Exact match
        feedDao.getByName(sourceName)?.let { return it }

        // 2. SQL LIKE containment (either direction)
        feedDao.getByNameFuzzy(sourceName)?.let { return it }

        // 3. Normalize: strip " World", " News", " (US)", etc. and retry
        val normalized = sourceName.replace(STRIP_SUFFIXES, " ").trim()
        if (normalized != sourceName && normalized.isNotEmpty()) {
            feedDao.getByName(normalized)?.let { return it }
            feedDao.getByNameFuzzy(normalized)?.let { return it }
        }

        return null
    }

    suspend fun scoreDiversity(cluster: StoryCluster, feedDao: FeedDao): DiversityScore {
        val feedNames = cluster.stories.map { it.sourceName }.distinct()

        val feeds = feedNames.mapNotNull { name -> findFeedForSource(name, feedDao) }

        if (feeds.size < feedNames.size) {
            val unmatched = feedNames.filter { name ->
                feeds.none { it.name.equals(name, ignoreCase = true) }
            }
            if (unmatched.isNotEmpty()) {
                Log.d(TAG, "DIVERSITY: ${unmatched.size} unmatched sources: ${unmatched.take(3)}")
            }
        }

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
