package com.globenews.core.common

import java.net.URI

fun String.normalizeUrl(): String {
    return try {
        val uri = URI(this.trim())
        val scheme = "https"
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return this
        val path = uri.path?.trimEnd('/') ?: ""
        "$scheme://$host$path"
    } catch (e: Exception) {
        this.trim()
    }
}

fun titleWordOverlap(a: String, b: String): Double {
    val wordsA = a.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
    val wordsB = b.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
    if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
    val intersection = wordsA.intersect(wordsB).size
    val smaller = minOf(wordsA.size, wordsB.size)
    return intersection.toDouble() / smaller.toDouble()
}
