package com.threadline.domain.usecase

import com.threadline.domain.model.NewsStory
import com.threadline.domain.model.StoryCluster

object StoryClusterer {

    fun clusterStories(stories: List<NewsStory>): List<StoryCluster> {
        val clusters = mutableListOf<StoryCluster>()
        val assigned = mutableSetOf<String>()

        val sorted = stories.sortedByDescending { it.publishedAt }

        for (story in sorted) {
            if (story.id in assigned) continue

            val similar = sorted.filter { other ->
                other.id != story.id
                    && other.id !in assigned
                    && titleSimilarity(story.title, other.title) > 0.4f
            }

            val clusterStories = listOf(story) + similar
            clusterStories.forEach { assigned.add(it.id) }

            clusters.add(
                StoryCluster(
                    id = story.id,
                    representativeTitle = story.title,
                    representativeSummary = story.summary,
                    stories = clusterStories,
                    sourceCount = clusterStories.map { it.sourceName }.distinct().size,
                    mostRecent = clusterStories.maxOf { it.publishedAt },
                    categories = clusterStories.map { it.category }.distinct()
                )
            )
        }
        return clusters
    }

    fun titleSimilarity(a: String, b: String): Float {
        val trigramsA = a.lowercase().windowed(3).toSet()
        val trigramsB = b.lowercase().windowed(3).toSet()
        if (trigramsA.isEmpty() || trigramsB.isEmpty()) return 0f
        val intersection = trigramsA.intersect(trigramsB).size
        val union = trigramsA.union(trigramsB).size
        return intersection.toFloat() / union.toFloat()
    }
}
