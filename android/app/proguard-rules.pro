# ---- kotlinx.serialization --------------------------------------------------
# The plugin generates serializers referenced only reflectively at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* {
    kotlinx.serialization.KSerializer serializer(...);
}

# Our DTOs are serialized by name; keep their fields.
-keep,includedescriptorclasses class com.fishmate.ai.data.remote.dto.** { *; }
-keep,includedescriptorclasses class com.fishmate.ai.live.protocol.** { *; }

# ---- Retrofit / OkHttp -----------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---- Hilt ------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
