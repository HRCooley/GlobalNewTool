package com.threadline.domain.model

data class ManagedFeed(
    val id: String,
    val name: String,
    val url: String,
    val country: String?,
    val language: String?,
    val latitude: Double?,
    val longitude: Double?,
    val scope: String,
    val category: String?,
    val enabled: Boolean,
    val consecutiveFailures: Int,
    val lastError: String?
)
