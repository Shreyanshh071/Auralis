# ── General Optimization & Kotlin Serialization ──
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ── Auralis Domain & Data Models (Serialization / JSON / DB) ──
-keep class com.auralis.music.domain.model.** { *; }
-keep class com.auralis.music.domain.recognition.** { *; }
-keep class com.auralis.music.data.model.** { *; }
-keep class com.auralis.music.data.local.** { *; }
-keep class com.auralis.music.data.network.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Serializable <methods>;
}

# ── Room Database ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Entity class * { *; }

# ── Kotlin Coroutines & Flow ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── OkHttp & Okio ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Coil Image Loading ──
-dontwarn coil.**
-keep class coil.** { *; }

# ── Media3 & ExoPlayer ──
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Mozilla Rhino JS & NewPipe Extractor ──
-keep class org.mozilla.javascript.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**

# ── Firebase & Google Play Services ──
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
