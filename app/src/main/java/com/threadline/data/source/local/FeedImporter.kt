package com.threadline.data.source.local

import android.content.Context
import android.util.Log
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.entity.ManagedFeedEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedDao: FeedDao
) {
    suspend fun importIfNeeded() {
        try {
            val existingCount = feedDao.getEnabledCount()
            if (existingCount > 10) {
                Log.d("Threadline", "FeedImporter: $existingCount feeds in DB, skipping")
                return
            }

            val json = context.assets.open("master_feeds.json").bufferedReader().readText()
            val root = JSONObject(json)
            val array = root.getJSONArray("feeds")

            var currentCategory = "uncategorized"
            val feeds = mutableListOf<ManagedFeedEntity>()

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

                val region = obj.optString("region", null)
                val politicalLean = obj.optString("politicalLean", null)
                val ownership = obj.optString("ownership", null)
                val ownerName = obj.optString("ownerName", null)

                feeds.add(
                    ManagedFeedEntity(
                        id = UUID.randomUUID().toString(),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        country = obj.optString("country", "").ifBlank { null },
                        language = obj.optString("language", "en").ifBlank { "en" },
                        latitude = obj.optDouble("lat", 0.0),
                        longitude = obj.optDouble("lon", 0.0),
                        scope = obj.optString("scope", "NATIONAL"),
                        category = currentCategory,
                        region = region,
                        politicalLean = politicalLean,
                        ownership = ownership,
                        ownerName = ownerName,
                        bundledRegion = region,
                        bundledPoliticalLean = politicalLean,
                        bundledOwnership = ownership,
                        bundledOwnerName = ownerName,
                        tagsModifiedByUser = false,
                        enabled = true,
                        isBundled = true,
                        lastFetchAt = null,
                        lastSuccessAt = null,
                        lastError = null
                    )
                )
            }

            feedDao.upsertAll(feeds)
            Log.d("Threadline", "FeedImporter: imported ${feeds.size} feeds")
        } catch (e: Exception) {
            Log.e("Threadline", "FeedImporter: import failed: ${e.message}", e)
        }
    }
}
