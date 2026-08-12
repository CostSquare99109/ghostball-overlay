# Add project-specific ProGuard rules here.
# Keep JUnit / Kotlin metadata is automatic via the default files.

# Service: the manifest declaration is the source of truth — keep names.
-keep class com.johan.ghostball.OverlayService { *; }

# Logcat strings trimmed in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
