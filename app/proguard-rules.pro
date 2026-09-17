# --------------------------------------------------------------------------------------------------
# Glide Release Candidate ProGuard & R8 Optimization Configuration
# --------------------------------------------------------------------------------------------------

# Preserve source file names and line numbers for deobfuscated crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve standard annotations and inner class relationships
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --------------------------------------------------------------------------------------------------
# Android Core & Jetpack Architecture Components
# --------------------------------------------------------------------------------------------------

# Android Lifecycle ViewModels
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Android WorkManager Workers
-keep class * extends androidx.work.Worker {
    <init>(...);
}
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# Services & Receivers
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver

# --------------------------------------------------------------------------------------------------
# Google Play Services Nearby Connections
# --------------------------------------------------------------------------------------------------
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.nearby.**

# --------------------------------------------------------------------------------------------------
# Dagger / Hilt Dependency Injection
# --------------------------------------------------------------------------------------------------
-keep class * implements dagger.hilt.internal.GeneratedComponent
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# --------------------------------------------------------------------------------------------------
# AndroidX Room & SQLite
# --------------------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep class dev.glide.data.db.** { *; }
-dontwarn androidx.room.paging.**

# --------------------------------------------------------------------------------------------------
# Kotlinx Serialization & Core Domain Protocols
# --------------------------------------------------------------------------------------------------
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$$serializer {
    public static final ** INSTANCE;
}
-keepclasseswithmembers class * {
    *** Companion;
}
-keep class dev.glide.core.domain.** { *; }
-keep class dev.glide.core.coordinator.** { *; }
-keep class dev.glide.core.pairing.** { *; }
-keep class dev.glide.core.routing.** { *; }
-keep class dev.glide.core.discovery.** { *; }
-keep class dev.glide.transfer.core.model.** { *; }

# --------------------------------------------------------------------------------------------------
# AndroidX CameraX (LAN-017)
# --------------------------------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --------------------------------------------------------------------------------------------------
# ZXing Core QR Code Engine (LAN-017)
# --------------------------------------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --------------------------------------------------------------------------------------------------
# Local Area Network (LAN) Transport & TLS Adapter (LAN-009..012)
# --------------------------------------------------------------------------------------------------
-keep class dev.glide.transport.lan.** { *; }

# --------------------------------------------------------------------------------------------------
# Optimization: Remove verbose and debug logging in release builds
# --------------------------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

