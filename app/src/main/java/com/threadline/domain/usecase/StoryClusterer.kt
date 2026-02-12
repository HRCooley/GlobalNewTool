package com.threadline.domain.usecase

import android.util.Log
import com.threadline.domain.model.NewsStory
import com.threadline.domain.model.StoryCluster

object StoryClusterer {

    private const val TAG = "StoryClusterer"
    private const val SIMILARITY_THRESHOLD = 0.3f

    private val STOPWORDS = setOf(
        "the", "a", "an", "in", "on", "at", "to", "for",
        "of", "and", "or", "is", "are", "was", "were", "has", "have", "had",
        "be", "been", "being", "with", "from", "by", "as", "its", "it", "this",
        "that", "which", "who", "whom", "after", "before", "says", "said", "new",
        "not", "but", "over", "out", "up", "all", "about", "into", "more", "than"
    )

    fun clusterStories(stories: List<NewsStory>): List<StoryCluster> {
        val clusters = mutableListOf<StoryCluster>()
        val assigned = mutableSetOf<String>()

        val sorted = stories.sortedByDescending { it.publishedAt }

        for (story in sorted) {
            if (story.id in assigned) continue

            val similar = sorted.filter { other ->
                other.id != story.id
                    && other.id !in assigned
                    && titleSimilarity(story.title, other.title) > SIMILARITY_THRESHOLD
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

        Log.d(TAG, "Clustered ${stories.size} stories into ${clusters.size} clusters")
        clusters.filter { it.sourceCount > 1 }.forEach {
            Log.d(TAG, "  Cluster: ${it.representativeTitle} — ${it.sourceCount} sources")
        }

        return clusters
    }

    fun titleSimilarity(a: String, b: String): Float {
        val wordsA = a.lowercase().split(Regex("\\W+"))
            .filter { it.length > 2 && it !in STOPWORDS }.toSet()
        val wordsB = b.lowercase().split(Regex("\\W+"))
            .filter { it.length > 2 && it !in STOPWORDS }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return intersection.toFloat() / union.toFloat()
    }
}
