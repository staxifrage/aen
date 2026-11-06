# ProGuard configuration for OpenMotionEditor
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
