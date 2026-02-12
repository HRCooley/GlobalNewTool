package com.threadline.app

import android.app.Application
import com.threadline.data.source.local.FeedImporter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ThreadlineApp : Application() {

    @Inject lateinit var feedImporter: FeedImporter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            feedImporter.importIfNeeded()
        }
    }
}
