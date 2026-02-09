package com.globenews.core.common

object Constants {
    const val GDELT_BASE_URL = "https://api.gdeltproject.org/api/v2/doc/"
    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/"
    const val GOOGLE_NEWS_RSS_BASE = "https://news.google.com/rss/search"
    const val NEWSAPI_BASE_URL = "https://newsapi.org/v2/"
    const val GNEWS_BASE_URL = "https://gnews.io/api/v4/"

    const val GLOBAL_CACHE_MINUTES = 15L
    const val LOCAL_CACHE_MINUTES = 30L
    const val RSS_REFRESH_MINUTES = 20L

    const val GDELT_BATCH_SIZE = 5
    const val GDELT_BATCH_DELAY_MS = 500L

    const val MAX_MARKERS_HIGH_ALT = 1000
    const val MAX_MARKERS_MID_ALT = 750
    const val MAX_MARKERS_LOW_ALT = 500

    const val HIGH_ALTITUDE_KM = 8000.0
    const val LOW_ALTITUDE_KM = 1000.0

    const val DEDUP_TITLE_OVERLAP_THRESHOLD = 0.8
    const val DEDUP_TIME_WINDOW_HOURS = 12L
}
