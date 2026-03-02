# ============================================================
#  Browser Migrator — ProGuard / R8 Kuralları
# ============================================================

# ---- Kotlin ----
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlinx.coroutines.**

# ---- Model sınıfları ----
-keep class com.browsermover.app.model.** { *; }
-keep class com.browsermover.app.core.JsonPatcher$PatchResult { *; }
-keep class com.browsermover.app.root.CommandResult { *; }

# ---- Sealed class'lar ----
-keep class com.browsermover.app.model.MigrationResult { *; }
-keep class com.browsermover.app.model.MigrationResult$* { *; }

# ---- org.json ----
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---- BrowserAdapter ViewHolder ----
-keep class com.browsermover.app.ui.BrowserAdapter$ViewHolder { *; }

# ---- Genel Android ----
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
