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
-keep interface com.browsermover.app.IShellService { *; }
-keep class com.browsermover.app.IShellService$Stub { *; }
-keep class rikka.shizuku.** { *; }
