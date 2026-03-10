# ── LinkPi ProGuard / R8 Rules ──

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Data classes used in JSON serialization (org.json) ──
-keep class com.example.link_pi.data.model.** { *; }
-keep class com.example.link_pi.agent.ModuleStorage$Module { *; }
-keep class com.example.link_pi.agent.ModuleStorage$Endpoint { *; }
-keep class com.example.link_pi.share.ReceivedItem { *; }
-keep class com.example.link_pi.share.ConnectionState$* { *; }

# ── Enums resolved by valueOf() during deserialization ──
-keepclassmembers enum com.example.link_pi.skill.BridgeGroup { *; }
-keepclassmembers enum com.example.link_pi.skill.CdnGroup { *; }
-keepclassmembers enum com.example.link_pi.skill.ToolGroup { *; }
-keepclassmembers enum com.example.link_pi.skill.UserIntent { *; }
-keepclassmembers enum com.example.link_pi.data.model.SkillMode { *; }

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Java-WebSocket ──
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ── JSch (SSH) ──
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ── Google Tink / errorprone annotations ──
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ── Kotlin coroutines ──
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── Compose ──
-dontwarn androidx.compose.**

# ── WebView JavaScript interface ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}