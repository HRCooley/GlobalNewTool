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

    const val MAX_MARKERS_WORLD = 1000
    const val MAX_MARKERS_REGION = 750
    const val MAX_MARKERS_LOCAL = 500

    // Zoom thresholds for Leaflet (zoom 2-18)
    // zoom < 4 = world view, 4-8 = continental, >8 = city/region
    const val ZOOM_WORLD_THRESHOLD = 4.0
    const val ZOOM_LOCAL_THRESHOLD = 8.0

    const val DEDUP_TITLE_OVERLAP_THRESHOLD = 0.8
    const val DEDUP_TIME_WINDOW_HOURS = 12L
}
