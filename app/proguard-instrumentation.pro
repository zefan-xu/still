# Applied only when CI minifies the debug APK the device and live suites install.
# kotlin-stdlib is an app dependency, so R8 leaves the runner's references in the
# test APK and expects the classes in the app dex. Keeping one facade (Lambda)
# just moved the crash to the next one (LazyKt). Keep the stdlib.
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Coroutines are on the instrumentation classpath only. Keep them in the test APK.
-keep class kotlinx.** { *; }
-dontwarn kotlinx.**

# Espresso and the app both depend on these. The app never references them, so R8
# drops them and the test APK still expects them in the app dex.
-keep class com.google.common.util.concurrent.** { *; }
-dontwarn com.google.common.util.concurrent.**
-keep class androidx.concurrent.futures.** { *; }
-dontwarn androidx.concurrent.futures.**

# Instrumentation calls members the app itself does not (the two-argument Media
# constructor, ShareActivity.session, PlayerActivity.pageCount). Release shrinking
# still removes unused app members; this file is only on the minified debug APK.
-keep class org.still.saver.** { *; }
