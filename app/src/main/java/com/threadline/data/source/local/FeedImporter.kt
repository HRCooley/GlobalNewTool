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
    companion object {
        private const val TAG = "FeedImporter"
        private const val PREFS_NAME = "feed_importer"
        private const val KEY_IMPORTED_VERSION = "imported_version"
    }

    suspend fun importIfNeeded() {
        try {
            val json = context.assets.open("master_feeds.json").bufferedReader().readText()
            val root = JSONObject(json)
            val fileVersion = root.optInt("version", 1)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val importedVersion = prefs.getInt(KEY_IMPORTED_VERSION, 0)

            if (importedVersion >= fileVersion) {
                val existingCount = feedDao.getEnabledCount()
                if (existingCount > 10) {
                    Log.d(TAG, "FeedImporter: v$fileVersion already imported, $existingCount feeds in DB")
                    return
                }
            }

            Log.d(TAG, "FeedImporter: importing v$fileVersion (was v$importedVersion)")

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

                val region = obj.optString("region", "").ifBlank { null }
                val politicalLean = obj.optString("politicalLean", "").ifBlank { null }
                val ownership = obj.optString("ownership", "").ifBlank { null }
                val ownerName = obj.optString("ownerName", "").ifBlank { null }

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
            prefs.edit().putInt(KEY_IMPORTED_VERSION, fileVersion).apply()
            Log.d(TAG, "FeedImporter: imported ${feeds.size} feeds (v$fileVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "FeedImporter: import failed: ${e.message}", e)
        }
    }
}
