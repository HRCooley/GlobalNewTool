# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers @com.squareup.moshi.JsonClass class * { *; }

# Keep JS bridge
-keepclassmembers class com.globenews.presentation.globe.GlobeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
