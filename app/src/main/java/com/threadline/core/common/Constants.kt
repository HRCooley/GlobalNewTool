package com.threadline.core.common

object Constants {
    const val GDELT_BASE_URL = "https://api.gdeltproject.org/api/v2/doc/"

    const val GLOBAL_CACHE_MINUTES = 15L
    const val RSS_REFRESH_MINUTES = 20L

    const val GDELT_BATCH_SIZE = 5
    const val GDELT_BATCH_DELAY_MS = 500L

    const val MAX_STORIES_PER_SOURCE = 500
    const val MAX_STORIES_TOTAL = 1000
    const val MAX_QUERIES_PER_SESSION = 150

    const val DEDUP_TITLE_OVERLAP_THRESHOLD = 0.8
    const val DEDUP_TIME_WINDOW_HOURS = 12L
}
