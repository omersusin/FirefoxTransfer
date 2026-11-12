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

# Shizuku UserService classes are loaded by name from a different process.
-keep class com.browsermover.app.root.ShellUserService { *; }
-keep class com.browsermover.app.IShellService { *; }
-keep class com.browsermover.app.IShellService$* { *; }
-keep class rikka.shizuku.** { *; }

# Pure-Java LevelDB/Snappy used for extension storage conversion.
-keep class org.iq80.** { *; }
-dontwarn org.iq80.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
