# Release installer shrinking. Only unused library code is removed (mostly the Material icons):
# no obfuscation or optimisation, and anything reached through reflection or native code is kept.
-dontobfuscate
-dontoptimize

# Auralis itself (small; JNA callbacks and data classes are reached reflectively).
-keep class com.auralis.music.** { *; }

# libVLC through VLCJ and JNA (native callbacks, structures).
-keep class uk.co.caprica.vlcj.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }

# YouTube extraction: NewPipe, its Rhino JavaScript engine, protobuf, jsoup, nanojson.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.firestore.v1.** { *; }
-keep class io.grpc.** { *; }
-keep class * implements io.grpc.ManagedChannelProvider { *; }
-keep class * implements io.grpc.NameResolverProvider { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }

# Networking and JSON.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }
-keep class org.sqlite.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# Image loading and the Swing main dispatcher (found through ServiceLoader).
-keep class coil3.** { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Optional dependencies these libraries probe for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**

# Skiko is pinned to 0.8.25 (build.gradle.kts), which added a SkiaLayerProperties parameter to
# SkiaSwingLayer's constructor. Compose 1.7.3's SwingSkiaLayerComponent still calls the old one.
# It only runs with compose.swing.render.on.graphics, which Auralis never sets (its windows use
# WindowSkiaLayerComponent), and the unshrunk build carries the same classes. Without this,
# ProGuard stops every release build.
-dontwarn androidx.compose.ui.scene.skia.SwingSkiaLayerComponent**
