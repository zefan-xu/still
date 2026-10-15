# yt-dlp runs through youtubedl-android. The bridge loads libpython by name and Jackson
# reflects over the mapper types, so R8 must not rename or remove them.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Platform org.json. JSONObject reflects when it wraps maps; keep a packaged copy intact too.
-keep class org.json.** { *; }
-dontwarn org.json.**

# Device tests look these PlayerActivity fields up by name.
-keepclassmembers class org.still.saver.PlayerActivity {
    private android.widget.VideoView video;
    private android.graphics.drawable.Drawable animation;
}
