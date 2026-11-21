# ZEE AI Companion ProGuard / R8 Rules
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod

# Keep Room Database entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep OkHttp & Kotlinx Serialization
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep Android Accessibility, VoiceInteraction, and NotificationListener Services
-keep class com.zeeai.companion.services.** { *; }
-keep class com.zeeai.companion.receivers.** { *; }
