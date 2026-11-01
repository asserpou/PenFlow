# PenFlow Proguard rules
-keep class com.penflow.tablet.model.** { *; }
-keepclassmembers class * {
    native <methods>;
}
