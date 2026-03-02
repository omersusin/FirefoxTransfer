#  Browser Migrator — ProGuard / R8 Rules
# ============================================================

# JSON support
-keep class org.json.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- Model classes ----
-keepclassmembers class com.browsermover.app.model.** { *; }
-keep class com.browsermover.app.model.** { *; }

# ViewBinding
-keep class com.browsermover.app.databinding.** { *; }
