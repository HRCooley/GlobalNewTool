package com.globenews.data.source.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedDao: ManagedFeedDao
) {
    suspend fun importIfNeeded() {
        try {
            val existingCount = feedDao.getEnabledCount()
            if (existingCount > 10) {
                Log.d("GlobeNews", "FeedImporter: $existingCount feeds already in DB, skipping import")
                return
            }

            val json = context.assets.open("globenews_master_feeds_full.json").bufferedReader().readText()
            val root = JSONObject(json)
            val array = root.getJSONArray("feeds")

            var currentCategory = "uncategorized"
            val feeds = mutableListOf<ManagedFeed>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.has("_section")) {
                    currentCategory = obj.getString("_section")
                        .replace("=", "").trim().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                    continue
                }
                if (!obj.has("name") || !obj.has("url")) continue

                feeds.add(
                    ManagedFeed(
                        id = UUID.randomUUID().toString(),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        country = obj.optString("country", ""),
                        language = obj.optString("language", "en"),
                        latitude = obj.optDouble("lat", 0.0),
                        longitude = obj.optDouble("lon", 0.0),
                        scope = obj.optString("scope", "NATIONAL"),
                        category = currentCategory,
                        enabled = true,
                        isBundled = true
                    )
                )
            }

            feedDao.upsertAll(feeds)
            Log.d("GlobeNews", "FeedImporter: imported ${feeds.size} feeds from globenews_master_feeds_full.json")
        } catch (e: Exception) {
            Log.e("GlobeNews", "FeedImporter: import failed: ${e.message}", e)
        }
    }
}
