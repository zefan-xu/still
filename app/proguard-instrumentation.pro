# Applied only when CI minifies the debug APK the device and live suites install.
# kotlin-stdlib is an app dependency, so R8 leaves the runner's references in the
# test APK and expects the classes in the app dex. Keeping one facade (Lambda)
# just moved the crash to the next one (LazyKt). Keep the stdlib.
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Coroutines are on the instrumentation classpath only. Keep them in the test APK.
-keep class kotlinx.** { *; }
-dontwarn kotlinx.**
