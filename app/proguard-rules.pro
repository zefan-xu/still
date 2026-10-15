# yt-dlp runs through youtubedl-android. The bridge loads libpython by name and Jackson
# reflects over the mapper types, so R8 must not rename or remove them.
-keep class com.yausername.youtubedl_android.** { *; }
# The instrumentation runner's FileTestStorage is Kotlin. Minifying the test APK drops
# kotlin.jvm.internal.Lambda unless it is kept in both the app and the test.
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Platform org.json. JSONObject reflects when it wraps maps; keep a packaged copy intact too.
-keep class org.json.** { *; }
-dontwarn org.json.**

# Error Prone's annotations mention a compiler type that is not on the Android test classpath.
-dontwarn javax.lang.model.element.Modifier

# Device tests look these PlayerActivity fields up by name.
-keepclassmembers class org.still.saver.PlayerActivity {
    private android.widget.VideoView video;
    private android.graphics.drawable.Drawable animation;
}
