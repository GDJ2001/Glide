# Glide Android Workspace

This directory contains all Android and Kotlin platform development components for Glide.

## Structure

```
android/
├── app/             # Main Android Application module (:android:app)
├── core/            # Pure JVM Kotlin domain transfer engine (:android:core, 0 Android framework dependencies)
├── data/            # Room database and disk persistence module (:android:data)
├── designsystem/    # Jetpack Compose design tokens, components, and themes (:android:designsystem)
├── transport-lan/   # Android NSD (mDNS) and TLS socket transport (:android:transport-lan)
├── transport-nearby/# Android Google Play Services Nearby Connections transport (:android:transport-nearby)
├── transport-webrtc/# Android WebRTC adapter (:android:transport-webrtc)
└── benchmark/       # Android Macrobenchmark performance suite (:android:benchmark)
```

## Building and Testing

Run all unit tests:
```bash
./gradlew test
```

Assemble debug APK:
```bash
./gradlew :android:app:assembleDebug
```
